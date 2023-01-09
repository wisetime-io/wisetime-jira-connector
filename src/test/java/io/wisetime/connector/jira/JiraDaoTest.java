/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.ConnectorLauncher.JiraDbModule;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;
import static io.wisetime.connector.jira.RandomDataGenerator.randomIssue;
import static io.wisetime.connector.jira.RandomDataGenerator.randomIssues;
import static io.wisetime.connector.jira.RandomDataGenerator.randomWorklog;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.javafaker.Faker;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.zaxxer.hikari.HikariDataSource;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.data.MapEntry;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author shane.xie
 * @author alvin.llobrera
 */
class JiraDaoTest {

  private static final String TEST_JDBC_URL = "jdbc:h2:mem:test_jira_db;DB_CLOSE_DELAY=-1";
  private static final Faker FAKER = new Faker();
  private static JiraDao jiraDao;
  private static FluentJdbc fluentJdbc;

  @BeforeAll
  static void setup() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_JDBC_URL, TEST_JDBC_URL);
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_ISSUE_URL_PREFIX, FAKER.internet().url() + "/");
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_DB_USER, "test");
    RuntimeConfig.setProperty(JiraConnectorConfigKey.JIRA_DB_PASSWORD, "test");

    final Injector injector = Guice.createInjector(
        new JiraDbModule(), new FlywayJiraTestDbModule()
    );

    jiraDao = injector.getInstance(JiraDao.class);
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(injector.getInstance(HikariDataSource.class)).build();

    // Apply Jira DB schema to test db
    injector.getInstance(Flyway.class).migrate();
  }

  @AfterAll
  static void tearDown() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_JDBC_URL);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_DB_USER);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_DB_PASSWORD);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.JIRA_ISSUE_URL_PREFIX);
  }

  @BeforeEach
  void setupTests() {
    Preconditions.checkState(
        // We don't want to accidentally truncate production tables
        RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_URL).orElse("").equals(TEST_JDBC_URL)
    );
    Query query = fluentJdbc.query();
    query.update("DELETE FROM project").run();
    query.update("DELETE FROM issuetype").run();
    query.update("DELETE FROM jiraissue").run();
    query.update("DELETE FROM cwd_user").run();
    query.update("DELETE FROM worklog").run();
    query.update("DELETE FROM sequence_value_item").run();
    query.update("DELETE FROM propertyentry").run();
    query.update("DELETE FROM propertystring").run();
  }

  @Test
  void emptyTransaction() {
    // Will throw an exception if the select query is not executed with the same query object that started the transaction
    // because of internal working of fluent jdbc
    jiraDao.asTransaction(() -> {
      jiraDao.pingDb();
      jiraDao.findIssueByTagName("Not a jira tag");
    });
  }

  @Test
  void toUpsertTagRequest() {
    final Issue issue = randomIssue();
    final UpsertTagRequest request = issue.toUpsertTagRequest("/Jira/");
    final String tagName = issue.getProjectKey() + "-" + issue.getIssueNumber();

    assertThat(request)
        .isEqualTo(new UpsertTagRequest()
            .name(tagName)
            .description(issue.getSummary())
            .additionalKeywords(ImmutableList.of(tagName))
            .path("/Jira/")
            .externalId(issue.getId() + "")
            .metadata(Map.of(
                "Project", issue.getProjectKey(),
                "Type", issue.getIssueType()
            )));
  }

  @Test
  void generateMetadata() {
    final Issue issue = randomIssue();
    assertThat(issue.generateMetadata())
        .containsExactly(
            MapEntry.entry("Project", issue.getProjectKey()),
            MapEntry.entry("Type", issue.getIssueType()));
  }

  @Test
  void generateMetadata_empty() {
    final Issue issue = randomIssue()
        .setIssueType("")
        .setProjectKey("");
    assertThat(issue.generateMetadata())
        .containsExactly(
            MapEntry.entry("Project", null),
            MapEntry.entry("Type", null));
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
  void pingDb() {
    assertThat(jiraDao.pingDb())
        .as("DB should be accessible")
        .isTrue();
  }

  @Test
  void issueCount_none_found() {
    assertThat(jiraDao.issueCount())
        .as("There are no issues in the database")
        .isEqualTo(0);
  }

  @Test
  void issueCount_all_projects() {
    insertRandomIssueToDb();
    insertRandomIssueToDb();

    assertThat(jiraDao.issueCount())
        .as("Issues from all projects should be counted")
        .isEqualTo(2);
  }

  @Test
  void issueCount_specific_projects() {
    saveProject(1L, "WT");
    saveJiraIssue(1L, randomIssue());
    saveJiraIssue(1L, randomIssue());

    saveProject(2L, "JI");
    saveJiraIssue(2L, randomIssue());

    assertThat(jiraDao.issueCount("WT"))
        .as("Only issues from the WT project should be counted")
        .isEqualTo(2);
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

    final List<Issue> wtIssues = randomIssues(10);
    List<Issue> savedWtIssues = IntStream.range(0, 10)
        .mapToObj(idx ->
            wtIssues.get(idx).toBuilder()
                .id(idx + 1)  // IDs start from 1
                .projectKey("WT")
                .build()
        )
        .peek(issue -> saveJiraIssue(1L, issue))
        .collect(Collectors.toList());

    final Issue otherIssue = randomIssue("OTHER-1")
        .setId(11);
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
  void findUsernameByEmail() {
    fluentJdbc.query().update("INSERT INTO cwd_user (id, user_name, lower_email_address) VALUES (1, ?, ?)")
        .params("foobar", "foobar@baz.com")
        .run();

    assertThat(jiraDao.findUsernameByEmail("foobar@baz.com").get())
        .as("Username linked to the email should be returned if email is valid.")
        .isEqualTo("foobar");
    assertThat(jiraDao.findUsernameByEmail("Foobar@baz.com").get())
        .as("Email should not be case sensitive")
        .isEqualTo("foobar");
    assertThat(jiraDao.findUsernameByEmail("foo.bar@baz.com"))
        .as("Should return empty if email is not found in DB")
        .isEmpty();
  }

  @Test
  void userExists() {
    fluentJdbc.query().update("INSERT INTO cwd_user (id, user_name, lower_user_name) VALUES (1, ?, ?)")
        .params("FooBar", "foobar")
        .run();

    assertThat(jiraDao.userExists("foobar"))
        .as("username exists in DB")
        .isTrue();
    assertThat(jiraDao.userExists("FOOBAR"))
        .as("Username should not be case sensitive")
        .isTrue();
    assertThat(jiraDao.userExists("foo.bar"))
        .as("username not in DB")
        .isFalse();
  }

  @Test
  void updateIssueTimeSpent() {
    final Long projectId = 1L;
    final Issue issue = randomIssue().setTimeSpent(200);
    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);

    jiraDao.updateIssueTimeSpent(issue.getId(), 700);

    assertThat(jiraDao.findIssueByTagName(issue.getProjectKey() + "-" + issue.getIssueNumber()).get().getTimeSpent())
        .as("Should be able to update total time spent")
        .isEqualTo(700);
  }

  @Test
  void createWorklog_newRecord() {
    Worklog workLog = randomWorklog();
    final Optional<Long> startingWorklogId = jiraDao.getWorklogSeqId();
    assertThat(startingWorklogId).isEmpty();

    jiraDao.createWorklog(workLog);


    assertThat(getWorklog(10299).get()) // 10299 is the starting worklog seq id we set if table is empty
        .as("Worklog should be saved exactly as provided")
        .isEqualTo(workLog);
  }

  @Test
  void createWorklog_withExistingWorklog() {
    // Create initial worklog
    final Worklog workLogUtc = randomWorklog();
    jiraDao.createWorklog(workLogUtc);

    // Create another worklog
    Worklog anotherWorkLog = randomWorklog();
    final Optional<Long> currentWorkLogId = jiraDao.getWorklogSeqId();
    assertThat(currentWorkLogId)
        .as("Should contain the worklog ID of the previously created worklog")
        .isPresent();
    jiraDao.createWorklog(anotherWorkLog);

    // Check the latest created worklog
    Worklog savedWorklog = getWorklog(currentWorkLogId.get() + 199).get();

    assertThat(savedWorklog) // we increment 199 to generate new worklog seq ID
        .as("Worklog should be saved with created time set in the timezone specified")
        .isEqualTo(anotherWorkLog);
  }

  private void saveProject(Long projecId, String projectKey) {
    fluentJdbc.query().update("INSERT INTO project (id, pkey) VALUES (?, ?)")
        .params(projecId, projectKey)
        .run();
  }

  private void saveJiraIssue(Long projectId, Issue issue) {
    final String issueTypeId = FAKER.internet().slug();
    fluentJdbc.query().update("INSERT INTO issuetype (id, pname) VALUES (?, ?)")
        .params(issueTypeId, issue.getIssueType())
        .run();

    fluentJdbc.query().update("INSERT INTO jiraissue (id, project, issuenum, summary, timespent, issuetype) "
        + "VALUES (?, ?, ?, ?, ?, ?)")
        .params(
            issue.getId(),
            projectId,
            issue.getIssueNumber(),
            issue.getSummary(),
            issue.getTimeSpent(),
            issueTypeId
        )
        .run();
  }

  private Issue insertRandomIssueToDb() {
    final Long projectId = FAKER.number().randomNumber();
    final Issue issue = randomIssue();

    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);
    return issue;
  }

  private Optional<Worklog> getWorklog(final long worklogId) {
    return fluentJdbc.query().select("SELECT issueid, author, timeworked, created, worklogbody FROM worklog WHERE id = ?")
        .params(worklogId)
        .firstResult(resultSet -> new Worklog()
            .setIssueId(resultSet.getLong(1))
            .setAuthor(resultSet.getString(2))
            .setTimeWorked(resultSet.getLong(3))
            .setCreated(resultSet.getTimestamp("created").toInstant())
            .setBody(resultSet.getString(5))
        );
  }

  public static class FlywayJiraTestDbModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(Flyway.class).toProvider(FlywayJiraProvider.class);
    }

    private static class FlywayJiraProvider implements Provider<Flyway> {

      @Inject
      private Provider<HikariDataSource> dataSourceProvider;

      @Override
      public Flyway get() {
        return new Flyway(new FluentConfiguration()
            .dataSource(dataSourceProvider.get())
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .baselineOnMigrate(true)
            .locations("jira_db_schema/"));
      }
    }
  }
}
