/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.testutils.FakeEntities;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;
import spark.Request;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author shane.xie#practiceinsight.io
 */
class JiraConnectorPostTimeTest {

  private static JiraDao jiraDao = mock(JiraDao.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static TemplateFormatter templateFormatter = mock(TemplateFormatter.class);
  private static JiraConnector connector;
  private static FakeEntities fakeEntities = new FakeEntities();
  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();

  @BeforeAll
  static void setUp() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, String.valueOf(100));
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH, "/test/path/");
    RuntimeConfig.clearProperty(ConnectorConfigKey.CALLER_KEY);

    assertThat(RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY))
        .as("CALLER_KEY empty value expected")
        .isNotPresent();

    assertThat(RuntimeConfig.getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE))
        .as("TAG_UPSERT_BATCH_SIZE should be set to 100")
        .contains(100);

    connector = Guice.createInjector(binder -> {
      binder.bind(JiraDao.class).toProvider(() -> jiraDao);
    }).getInstance(JiraConnector.class);

    // Ensure JiraConnector#init will not fail
    doReturn(true).when(jiraDao).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClient, templateFormatter, mock(ConnectorStore.class)));
  }

  @AfterAll
  static void tearDown() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH);

    assertThat(RuntimeConfig.getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE))
        .as("TAG_UPSERT_BATCH_SIZE empty result expected")
        .isNotPresent();
    assertThat(RuntimeConfig.getString(JiraConnectorConfigKey.TAG_UPSERT_PATH))
        .isNotPresent();
  }

  @BeforeEach
  void setUpTest() {
    reset(templateFormatter);
    reset(jiraDao);

    // Ensure that code in the transaction lambda gets exercised
    doAnswer(invocation -> {
      invocation.<Runnable>getArgument(0).run();
      return null;
    }).when(jiraDao).asTransaction(any(Runnable.class));
  }

  @Test
  void postTime_without_tags_should_succeed() {
    final TimeGroup groupWithNoTags = fakeEntities.randomTimeGroup().tags(ImmutableList.of());

    assertThat(connector.postTime(fakeRequest(), groupWithNoTags))
        .isEqualTo(PostResult.SUCCESS)
        .as("There is nothing to post to Jira");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_with_invalid_caller_key_should_fail() {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, "caller-key");

    final TimeGroup groupWithNoTags = fakeEntities
        .randomTimeGroup()
        .callerKey("wrong-key")
        .tags(ImmutableList.of());

    assertThat(connector.postTime(fakeRequest(), groupWithNoTags))
        .isEqualTo(PostResult.PERMANENT_FAILURE)
        .as("Invalid caller key should result in post failure");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_with_valid_caller_key_should_succeed() {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, "caller-key");

    final TimeGroup groupWithNoTags = fakeEntities
        .randomTimeGroup()
        .callerKey("caller-key")
        .tags(ImmutableList.of());

    assertThat(connector.postTime(fakeRequest(), groupWithNoTags))
        .isEqualTo(PostResult.SUCCESS)
        .as("Posting time with valid caller key should succeed");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_without_time_rows_should_fail() {
    final TimeGroup groupWithNoTimeRows = fakeEntities.randomTimeGroup().timeRows(ImmutableList.of());

    assertThat(connector.postTime(fakeRequest(), groupWithNoTimeRows))
        .isEqualTo(PostResult.PERMANENT_FAILURE)
        .as("Group with no time is invalid");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_cant_find_user() {
    when(jiraDao.findUsername(anyString())).thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), fakeEntities.randomTimeGroup()))
        .isEqualTo(PostResult.PERMANENT_FAILURE)
        .as("Can't post time because user doesn't exist in Jira");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_cant_find_issue() {
    when(jiraDao.findIssueByTagName(anyString())).thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), fakeEntities.randomTimeGroup()))
        .isEqualTo(PostResult.PERMANENT_FAILURE)
        .as("Can't post time because tag doesn't match any issue in Jira");

    verifyJiraNotUpdated();
  }

  @Test
  void postTime_db_transaction_error() {
    final TimeGroup timeGroup = fakeEntities.randomTimeGroup();

    when(jiraDao.findUsername(anyString()))
        .thenReturn(Optional.of(timeGroup.getUser().getExternalId()));

    final Tag tag = fakeEntities.randomTag("/Jira/");
    final Issue issue = randomDataGenerator.randomIssue(tag.getName());

    when(jiraDao.findIssueByTagName(anyString())).thenReturn(Optional.of(issue));
    when(templateFormatter.format(any(TimeGroup.class))).thenReturn("Work log body");
    doThrow(new RuntimeException("Test exception")).when(jiraDao).createWorklog(any(Worklog.class));

    final PostResult result = connector.postTime(fakeRequest(), fakeEntities.randomTimeGroup());

    assertThat(result)
        .isEqualTo(PostResult.TRANSIENT_FAILURE)
        .as("Database transaction error while posting time should result in transient failure");

    assertThat(result.getError().get())
        .isInstanceOf(RuntimeException.class)
        .as("Post result should contain the cause of the error");
  }

  @Test
  void postTime_with_valid_group_should_succeed() {
    final Tag tag1 = fakeEntities.randomTag("/Jira/");
    final Tag tag2 = fakeEntities.randomTag("/Jira/");
    final Tag tag3 = fakeEntities.randomTag("/Jira/");

    final TimeRow timeRow1 = fakeEntities.randomTimeRow().activityHour(2018110110);
    final TimeRow timeRow2 = fakeEntities.randomTimeRow().activityHour(2018110109);

    final User user = fakeEntities.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = fakeEntities.randomTimeGroup()
        .tags(ImmutableList.of(tag1, tag2, tag3))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(1500);

    when(jiraDao.findUsername(anyString()))
        .thenReturn(Optional.of(timeGroup.getUser().getExternalId()));

    final Issue issue1 = randomDataGenerator.randomIssue(tag1.getName());
    final Issue issue2 = randomDataGenerator.randomIssue(tag1.getName());

    when(jiraDao.findIssueByTagName(anyString()))
        .thenReturn(Optional.of(issue1))
        .thenReturn(Optional.of(issue2))
        // Last tag has no matching Jira issue
        .thenReturn(Optional.empty());

    when(templateFormatter.format(any(TimeGroup.class)))
        .thenReturn("Work log body");

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .isEqualTo(PostResult.SUCCESS)
        .as("Valid time group should be posted successfully");

    // Verify worklog creation
    ArgumentCaptor<Worklog> worklogCaptor = ArgumentCaptor.forClass(Worklog.class);
    verify(jiraDao, times(2)).createWorklog(worklogCaptor.capture());
    List<Worklog> createdWorklogs = worklogCaptor.getAllValues();

    assertThat(createdWorklogs.get(0).getIssueId())
        .isEqualTo(issue1.getId())
        .as("The worklog should be assigned to the right issue");

    assertThat(createdWorklogs.get(1).getIssueId())
        .isEqualTo(issue2.getId())
        .as("The worklog should be assigned to the right issue");

    assertThat(createdWorklogs.get(0).getAuthor())
        .isEqualTo(timeGroup.getUser().getExternalId())
        .as("The author should be set to the posted time user's external ID");

    assertThat(createdWorklogs.get(0).getBody())
        .isEqualTo("Work log body")
        .as("The worklog body should be set to the output of the template formatter");

    assertThat(createdWorklogs.get(0).getCreated())
        .isEqualTo(LocalDateTime.of(2018, 11, 1, 9, 0))
        .as("The worklog should be created with the earliest time row start time");

    assertThat(createdWorklogs.get(0).getTimeWorked())
        .isEqualTo(250)
        .as("The time worked should take into account the user's experience rating and" +
            "be split equally between the two tags");

    ArgumentCaptor<Long> idUpdateIssueCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> timeSpentUpdateIssueCaptor = ArgumentCaptor.forClass(Long.class);
    verify(jiraDao, times(2))
        .updateIssueTimeSpent(idUpdateIssueCaptor.capture(), timeSpentUpdateIssueCaptor.capture());

    List<Long> updatedIssueIds = idUpdateIssueCaptor.getAllValues();
    assertThat(updatedIssueIds)
        .containsExactly(issue1.getId(), issue2.getId())
        .as("Time spent of both matching issues should be updated");

    List<Long> updatedIssueTimes = timeSpentUpdateIssueCaptor.getAllValues();
    assertThat(updatedIssueTimes)
        .containsExactly(issue1.getTimeSpent() + 250, issue2.getTimeSpent() + 250)
        .as("Time spent of both matching issues should be updated with new duration. The duration should be " +
            "split among the three tags even if one of them was not found.");
  }

  private void verifyJiraNotUpdated() {
    verify(jiraDao, never()).updateIssueTimeSpent(anyLong(), anyLong());
    verify(jiraDao, never()).createWorklog(any(Worklog.class));
  }

  private Request fakeRequest() {
    return mock(Request.class);
  }
}
