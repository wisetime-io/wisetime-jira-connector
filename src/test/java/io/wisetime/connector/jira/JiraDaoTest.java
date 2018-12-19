/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import com.github.javafaker.Faker;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.generated.connect.UpsertTagRequest;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.ConnectorLauncher.JiraDbModule;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author shane.xie@practiceinsight.io
 * @author alvin.llobrera@practiceinsight.io
 */
class JiraDaoTest {

  private static final String TEST_JDBC_URL = "jdbc:h2:mem:test_jira_db;DB_CLOSE_DELAY=-1";
  private static final RandomDataGenerator RANDOM_DATA_GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();
  private static JiraDao jiraDao;
  private static FluentJdbc fluentJdbc;

  @BeforeAll
  static void setup() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_JDBC_URL, TEST_JDBC_URL);
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_JDBC_USER, "test");
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_JDBC_PASSWORD, "test");

    final Injector injector = Guice.createInjector(
        new JiraDbModule(), new FlywayJiraTestDbModule()
    );

    jiraDao = injector.getInstance(JiraDao.class);
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(injector.getInstance(DataSource.class)).build();

    // Apply Jira DB schema to test db
    injector.getInstance(Flyway.class).migrate();
  }

  @BeforeAll
  static void tearDown() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_JDBC_URL);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_JDBC_USER);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_JDBC_PASSWORD);
  }

  @BeforeEach
  void setupTests() {
    Preconditions.checkState(
        // We don't want to accidentally truncate production tables
        RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_URL).orElse("").equals(TEST_JDBC_URL)
    );
    Query query = fluentJdbc.query();
    query.update("DELETE FROM project").run();
    query.update("DELETE FROM jiraissue").run();
    query.update("DELETE FROM cwd_user").run();
    query.update("DELETE FROM worklog").run();
    query.update("DELETE FROM sequence_value_item").run();
    query.update("DELETE FROM propertyentry").run();
    query.update("DELETE FROM propertystring").run();
  }

  @Test
  void toUpsertTagRequest() {
    final Issue issue = RANDOM_DATA_GENERATOR.randomIssue();
    final UpsertTagRequest request = issue.toUpsertTagRequest("/Jira/");
    final String tagName = issue.getProjectKey() + "-" + issue.getIssueNumber();

    assertThat(request)
        .isEqualTo(new UpsertTagRequest()
            .name(tagName)
            .description(issue.getSummary())
            .additionalKeywords(ImmutableList.of(tagName))
            .path("/Jira/"));
  }

  @Test
  void hasExpectedSchema() {
    assertThat(jiraDao.hasExpectedSchema())
        .as("Flyway should freshly applied the expected Jira DB schema")
        .isTrue();

    Query query = fluentJdbc.query();
    query.update("ALTER TABLE project DROP pkey").run();
    assertThat(jiraDao.hasExpectedSchema())
        .as("A missing column should be detected")
        .isFalse();

    query.update("ALTER TABLE project ADD COLUMN pkey varchar(255) null").run();
    assertThat(jiraDao.hasExpectedSchema())
        .as("The missing column has been added")
        .isTrue();
  }

  @Test
  void hasConfiguredTimeZone() {
    assertThat(jiraDao.hasConfiguredTimeZone())
        .as("No timezone is set")
        .isFalse();

    saveDefaultTimeZone(1, "Asia/Manila");
    assertThat(jiraDao.hasConfiguredTimeZone())
        .as("Timezone is set")
        .isTrue();

    removedDefaultTimeZone(1);
    saveDefaultTimeZone(1, "Asia/Perth");
    assertThat(jiraDao.hasConfiguredTimeZone())
        .as("Timezone is unrecognized")
        .isFalse();
  }

  @Test
  void findIssueByTagName() {
    final Issue issue = insertRandomIssueToDb();

    assertThat(jiraDao.findIssueByTagName(issue.getProjectKey() + "-" + issue.getIssueNumber()))
        .as("Should return Jira issue if it exists in DB")
        .contains(issue);
    assertThat(jiraDao.findIssueByTagName(issue.getProjectKey() + "X-" + issue.getIssueNumber()))
        .as("Should return empty if tag name is not in DB")
        .isEmpty();
  }

  @Test
  void findIssueByTagName_incorrectFormat() {
    assertThat(jiraDao.findIssueByTagName("IAMAJIRAISSUE")).isEmpty();
    assertThat(jiraDao.findIssueByTagName("I-AM-A-JIRAISSUE")).isEmpty();
  }

  @Test
  void findIssuesOrderedById() {
    saveProject(1L, "WT");
    saveProject(2L, "OTHER");

    final List<Issue> wtIssues = RANDOM_DATA_GENERATOR.randomIssues(10);
    List<Issue> savedWtIssues = IntStream.range(0, 10)
        .mapToObj(idx ->
            Issue.builder()
                .from(wtIssues.get(idx))
                .id(idx + 1)  // IDs start from 1
                .projectKey("WT")
                .build()
        )
        .peek(issue -> saveJiraIssue(1L, issue))
        .collect(Collectors.toList());

    final Issue otherIssue = Issue
        .builder()
        .from(RANDOM_DATA_GENERATOR.randomIssue("OTHER-1"))
        .id(11)
        .build();
    saveJiraIssue(2L, otherIssue);

    final List<Issue> allIssues = ImmutableList
        .<Issue>builder()
        .addAll(savedWtIssues)
        .add(otherIssue)
        .build();

    assertThat(jiraDao.findIssuesOrderedById(0, 100))
        .as("Should be able retrieve all matching issues")
        .containsExactlyElementsOf(allIssues);
    assertThat(jiraDao.findIssuesOrderedById(0, 100, "WT"))
        .as("Should be able retrieve matching issue filtered by project key")
        .containsExactlyElementsOf(savedWtIssues);
    assertThat(jiraDao.findIssuesOrderedById(5, 5))
        .as("Should be able retrieve matching issues based on start ID")
        .containsExactlyElementsOf(savedWtIssues.subList(5, 10));
    assertThat(jiraDao.findIssuesOrderedById(13, 5))
        .as("Start ID is beyond range")
        .isEmpty();
  }

  @Test
  void findUsername() {
    fluentJdbc.query().update("INSERT INTO cwd_user (id, user_name, lower_email_address) VALUES (1, ?, ?)")
        .params("foobar", "foobar@baz.com")
        .run();

    assertThat(jiraDao.findUsername("foobar@baz.com").get())
        .as("Username should be returned if it exists in DB.")
        .isEqualTo("foobar");
    assertThat(jiraDao.findUsername("Foobar@baz.com").get())
        .as("Email should not be case sensitive")
        .isEqualTo("foobar");
    assertThat(jiraDao.findUsername("foo.bar@baz.com"))
        .as("Should return empty if email is not found in DB")
        .isEmpty();
  }

  @Test
  void updateIssueTimeSpent() {
    final Long projectId = 1L;
    final Issue issue = Issue.builder().from(RANDOM_DATA_GENERATOR.randomIssue()).timeSpent(200).build();
    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);

    jiraDao.updateIssueTimeSpent(issue.getId(), 700);

    assertThat(jiraDao.findIssueByTagName(issue.getProjectKey() + "-" + issue.getIssueNumber()).get().getTimeSpent())
        .as("Should be able to update total time spent")
        .isEqualTo(700);
  }

  @Test
  void createWorklog_newRecord() {
    final ZoneId sydneyTz = ZoneId.of("Australia/Sydney");
    final ZoneId perthTz = ZoneId.of("Australia/Perth");

    // Specify timezone to use
    saveDefaultTimeZone(1, perthTz.getId());

    Worklog workLogWithSydneyTz = RANDOM_DATA_GENERATOR.randomWorklog(sydneyTz);
    Worklog workLogWithPerthTz = Worklog.builder().from(workLogWithSydneyTz)
        .created(ZonedDateTime.of(workLogWithSydneyTz.getCreated(), ZoneOffset.UTC).toLocalDateTime().withNano(0))
        .build();

    final Optional<Long> startingWorklogId = jiraDao.getWorklogSeqId();
    assertThat(startingWorklogId).isEmpty();

    jiraDao.createWorklog(workLogWithSydneyTz);

    assertThat(getWorklog(10299).get()) // 10299 is the starting worklog seq id we set if table is empty
        .as("Worklog should be saved with created time set the zone specified")
        .isEqualTo(workLogWithPerthTz);
  }

  @Test
  void createWorklog_withExistingWorklog() {
    final ZoneId sydneyTz = ZoneId.of("Australia/Sydney");
    final ZoneId perthTz = ZoneId.of("Australia/Perth");

    // Specify timezone to use
    saveDefaultTimeZone(1, perthTz.getId());

    // Create worklog
    final Worklog workLog1WithSydneyTz = RANDOM_DATA_GENERATOR.randomWorklog(sydneyTz);
    jiraDao.createWorklog(workLog1WithSydneyTz);

    Worklog workLog2WithSydneyTz = RANDOM_DATA_GENERATOR.randomWorklog(sydneyTz);
    Worklog workLog2WithPerthTz = Worklog.builder().from(workLog2WithSydneyTz)
        .created(ZonedDateTime.of(workLog2WithSydneyTz.getCreated(), perthTz).toLocalDateTime().withNano(0))
        .build();
    final Optional<Long> currentWorkLogId = jiraDao.getWorklogSeqId();
    assertThat(currentWorkLogId)
        .as("Should contain the worklog ID of the previously created worklog")
        .isPresent();

    jiraDao.createWorklog(workLog2WithSydneyTz);

    assertThat(getWorklog(currentWorkLogId.get() + 199).get()) // we increment 199 to generate new worklog seq ID
        .as("Worklog should be saved with created time set in the timezone specified")
        .isEqualTo(workLog2WithPerthTz);
  }

  private void saveProject(Long projecId, String projectKey) {
    fluentJdbc.query().update("INSERT INTO project (id, pkey) VALUES (?, ?)")
        .params(projecId, projectKey)
        .run();
  }

  private void saveJiraIssue(Long projectId, Issue issue) {
    fluentJdbc.query().update("INSERT INTO jiraissue (id, project, issuenum, summary, timespent) VALUES (?, ?, ?, ?, ?)")
        .params(
            issue.getId(),
            projectId,
            issue.getIssueNumber(),
            issue.getSummary(),
            issue.getTimeSpent()
        )
        .run();
  }

  private Issue insertRandomIssueToDb() {
    final Long projectId = FAKER.number().randomNumber();
    final Issue issue = RANDOM_DATA_GENERATOR.randomIssue();

    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);
    return issue;
  }

  private Optional<Worklog> getWorklog(final long worklogId) {
    return fluentJdbc.query().select("SELECT issueid, author, timeworked, created, worklogbody FROM worklog WHERE id = ?")
        .params(worklogId)
        .firstResult(resultSet -> Worklog.builder()
            .issueId(resultSet.getLong(1))
            .author(resultSet.getString(2))
            .timeWorked(resultSet.getLong(3))
            .created(LocalDateTime.parse(resultSet.getString(4), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .body(resultSet.getString(5))
            .build()
        );
  }

  private void saveDefaultTimeZone(final int id, final String timezone) {
    fluentJdbc.query().update("INSERT INTO propertyentry (id, property_key) VALUES (?, 'jira.default.timezone')")
        .params(id)
        .run();

    fluentJdbc.query().update("INSERT INTO propertystring (id, propertyvalue) VALUES (?, ?)")
        .params(id)
        .params(timezone)
        .run();
  }

  private void removedDefaultTimeZone(final int id) {
    fluentJdbc.query().update("DELETE FROM propertyentry WHERE property_key = 'jira.default.timezone'").run();
    fluentJdbc.query().update("DELETE FROM propertystring WHERE id = ?").params(id).run();
  }

  public static class FlywayJiraTestDbModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(Flyway.class).toProvider(FlywayJiraProvider.class);
    }

    private static class FlywayJiraProvider implements Provider<Flyway> {

      @Inject
      private Provider<DataSource> dataSourceProvider;

      @Override
      public Flyway get() {
        Flyway flyway = new Flyway();
        flyway.setDataSource(dataSourceProvider.get());
        flyway.setBaselineVersion(MigrationVersion.fromVersion("0"));
        flyway.setBaselineOnMigrate(true);
        flyway.setLocations("jira_db_schema/");
        return flyway;
      }
    }
  }
}