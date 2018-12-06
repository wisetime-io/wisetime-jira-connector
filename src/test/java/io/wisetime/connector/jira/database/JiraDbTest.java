/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.base.Preconditions;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
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

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.jira.config.JiraConnectorConfigKey;
import io.wisetime.connector.jira.config.JiraConnectorModule;
import io.wisetime.connector.jira.models.ImmutableIssue;
import io.wisetime.connector.jira.models.ImmutableWorklog;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;
import io.wisetime.connector.jira.testutils.FakeEntities;
import io.wisetime.connector.jira.testutils.FlyAwayJiraTestDbModule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author shane.xie@practiceinsight.io
 * @author alvin.llobrera@practiceinsight.io
 */
class JiraDbTest {

  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static JiraDb jiraDb;
  private static Query query;

  @BeforeAll
  static void setup() {
    System.setProperty(JiraConnectorConfigKey.JIRA_JDBC_URL.getConfigKey(), "jdbc:h2:mem:test_jira_db;DB_CLOSE_DELAY=-1");
    System.setProperty(JiraConnectorConfigKey.JIRA_JDBC_USER.getConfigKey(), "test");
    System.setProperty(JiraConnectorConfigKey.JIRA_JDBC_PASSWORD.getConfigKey(), "test");

    final Injector injector = Guice.createInjector(
        new JiraConnectorModule(), new FlyAwayJiraTestDbModule()
    );

    jiraDb = injector.getInstance(JiraDb.class);
    query = injector.getInstance(Query.class);

    // Apply Jira DB schema update to test db
    injector.getInstance(Flyway.class).migrate();
  }

  @BeforeEach
  void setupTests() {
    Preconditions.checkState(
        RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_URL).orElse("")
            .equals("jdbc:h2:mem:test_jira_db;DB_CLOSE_DELAY=-1")
    );

    query.update("DELETE FROM project").run();
    query.update("DELETE FROM jiraissue").run();
    query.update("DELETE FROM cwd_user").run();
    query.update("DELETE FROM worklog").run();
    query.update("DELETE FROM sequence_value_item").run();
  }

  @Test
  void canUseDatabase() {
    assertThat(jiraDb.canUseDatabase())
        .as("flyway should freshly applied the expected Jira DB schema")
        .isTrue();

    query.update("ALTER TABLE project DROP pkey").run();
    assertThat(jiraDb.canUseDatabase())
        .as("a missing column should be detected")
        .isFalse();

    query.update("ALTER TABLE project ADD COLUMN pkey varchar(255) null").run();
    assertThat(jiraDb.canUseDatabase())
        .as("the missing column has been added")
        .isTrue();
  }

  @Test
  void findIssueByTagName() {
    Long projectId = 1L;
    Issue issue = FAKE_ENTITIES.randomIssue();

    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);

    assertThat(jiraDb.findIssueByTagName(issue.getProjectKey() + "-" + issue.getIssueNumber()))
        .as("should return Jira issue if existing in DB")
        .contains(issue);
    assertThat(jiraDb.findIssueByTagName(issue.getProjectKey() + "X-" + issue.getIssueNumber()))
        .as("should return empty if tag name is not in DB")
        .isEmpty();
  }

  @Test
  void findIssueByTagName_incorrectFormat() {
    assertThat(jiraDb.findIssueByTagName("IAMAJIRAISSUE")).isEmpty();
    assertThat(jiraDb.findIssueByTagName("I-AM-A-JIRAISSUE")).isEmpty();
  }

  @Test
  void findIssuesOrderedById() {
    final Long projectId = 1L;
    final String projectKey = "WT";
    List<Issue> issues = FAKE_ENTITIES.randomIssues(100);

    saveProject(projectId, projectKey);
    List<Issue> savedIssues = IntStream.range(0, issues.size())
        .mapToObj(idx -> ImmutableIssue.builder()
            .from(issues.get(idx))
            .id(idx + 1) // id starts in 1
            .projectKey(projectKey)
            .build())
        .peek(issue -> saveJiraIssue(projectId, issue))
        .collect(Collectors.toList());

    assertThat(jiraDb.findIssuesOrderedById(0, 100))
        .as("should be able retrieve matching issue")
        .containsExactlyElementsOf(savedIssues);
    assertThat(jiraDb.findIssuesOrderedById(25, 5))
        .as("should be able retrieve matching issue")
        .containsExactlyElementsOf(savedIssues.subList(25, 30));
    assertThat(jiraDb.findIssuesOrderedById(101, 5))
        .as("no jira issue should be returned when no issue matches the start id")
        .isEmpty();
  }

  @Test
  void findUsername() {
    query
        .update(
            String.format(
                "INSERT INTO cwd_user (id, user_name, lower_email_address) VALUES (1, '%s', '%s')",
                "foobar",
                "foobar@baz.com"
            )
        )
        .run();

    assertThat(jiraDb.findUsername("foobar@baz.com").get())
        .as("username should be returned if it exists in DB.")
        .isEqualTo("foobar");
    assertThat(jiraDb.findUsername("Foobar@baz.com").get())
        .as("email should not be case sensitive")
        .isEqualTo("foobar");
    assertThat(jiraDb.findUsername("foo.bar@baz.com"))
        .as("should return empty if email is not found in DB")
        .isEmpty();
  }

  @Test
  void updateIssueTimeSpent() {
    Long projectId = 1L;
    Issue issue = ImmutableIssue.builder().from(FAKE_ENTITIES.randomIssue()).timeSpent(200).build();
    saveProject(projectId, issue.getProjectKey());
    saveJiraIssue(projectId, issue);

    jiraDb.updateIssueTimeSpent(issue.getId(), 700);

    assertThat(jiraDb.findIssueByTagName(issue.getProjectKey() + "-" + issue.getIssueNumber()).get().getTimeSpent())
        .as("should be able to update total time spent")
        .isEqualTo(700);
  }

  @Test
  void createWorklog_newRecord() {
    Worklog workLogWithSydneyTz = FAKE_ENTITIES.randomWorklog(ZoneId.of("Australia/Sydney"));
    Worklog workLogWithUtcTz = ImmutableWorklog.builder().from(workLogWithSydneyTz)
        .created(ZonedDateTime.of(workLogWithSydneyTz.getCreated(), ZoneOffset.UTC).toLocalDateTime().withNano(0))
        .build();

    Optional<Long> startingWorklogId = jiraDb.getWorklogSeqId();
    assertThat(startingWorklogId).isEmpty();

    jiraDb.createWorklog(workLogWithSydneyTz);

    assertThat(getWorklog(10299).get()) // 10299 is the starting work log seq id we set if table is empty
        .as("work log should be saved with created time set the zone specified, default is UTC")
        .isEqualTo(workLogWithUtcTz);
  }

  @Test
  void createWorklog_withExistingWorklog() {
    // create work log
    Worklog workLog1WithSydneyTz = FAKE_ENTITIES.randomWorklog(ZoneId.of("Australia/Sydney"));
    jiraDb.createWorklog(workLog1WithSydneyTz);


    Worklog workLog2WithSydneyTz = FAKE_ENTITIES.randomWorklog(ZoneId.of("Australia/Sydney"));
    Worklog workLog2WithUtcTz = ImmutableWorklog.builder().from(workLog2WithSydneyTz)
        .created(ZonedDateTime.of(workLog2WithSydneyTz.getCreated(), ZoneOffset.UTC).toLocalDateTime().withNano(0))
        .build();
    Optional<Long> currentWorkLogId = jiraDb.getWorklogSeqId();
    assertThat(currentWorkLogId)
        .as("should contain the work log id of the previously created work log")
        .isPresent();

    jiraDb.createWorklog(workLog2WithSydneyTz);

    assertThat(getWorklog(currentWorkLogId.get() + 199).get()) // we increment 199 to generate new work log seq id
        .as("work log should be saved with created time set the zone specified, default is UTC")
        .isEqualTo(workLog2WithUtcTz);
  }

  private void saveProject(Long projecId, String projectKey) {
    query
        .update(
            String.format("INSERT INTO project (id, pkey) VALUES (%d, '%s')", projecId, projectKey)
        )
        .run();
  }

  private void saveJiraIssue(Long projectId, Issue issue) {
    query
        .update(
            String.format(
                "INSERT INTO jiraissue (id, project, issuenum, summary, timespent) VALUES (%d, %d, '%s', '%s', %d)",
                issue.getId(),
                projectId,
                issue.getIssueNumber(),
                issue.getSummary(),
                issue.getTimeSpent()
            )
        )
        .run();
  }

  private Optional<Worklog> getWorklog(long worklogId) {
    return query.select("SELECT issueid, author, timeworked, created, worklogbody FROM worklog WHERE id = ?")
        .params(worklogId)
        .firstResult(resultSet -> ImmutableWorklog.builder()
            .issueId(resultSet.getLong(1))
            .author(resultSet.getString(2))
            .timeWorked(resultSet.getLong(3))
            .created(LocalDateTime.parse(resultSet.getString(4), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .body(resultSet.getString(5))
            .build()
        );
  }
}