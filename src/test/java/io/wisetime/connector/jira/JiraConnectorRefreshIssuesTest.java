/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @author shane.xie
 */
class JiraConnectorRefreshIssuesTest {

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static ConnectorModule connectorModule;
  private static JiraDao jiraDao = mock(JiraDao.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static ConnectorStore connectorStore = mock(ConnectorStore.class);
  private static JiraConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH, "/test/path/");
    RuntimeConfig.setProperty(JiraConnectorConfigKey.PROJECT_KEYS_FILTER, "WT");

    connector = Guice.createInjector(binder -> {
      binder.bind(JiraDao.class).toProvider(() -> jiraDao);
    }).getInstance(JiraConnector.class);

    // Ensure JiraConnector#init will not fail
    doReturn(true).when(jiraDao).hasExpectedSchema();

    connectorModule = new ConnectorModule(apiClient, connectorStore, 5);
    connector.init(connectorModule);
  }

  @SuppressWarnings("Duplicates")
  @AfterAll
  static void tearDown() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.PROJECT_KEYS_FILTER);
  }

  @BeforeEach
  void setUpTest() {
    reset(jiraDao);
    reset(apiClient);
    reset(connectorStore);
  }

  @Test
  void refreshIssues_no_jira_issues_found() throws IOException {
    when(jiraDao.findIssuesOrderedById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.refreshIssues(10);
    verify(apiClient, never()).tagUpsertBatch(anyList());

    ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> storeValue = ArgumentCaptor.forClass(Long.class);
    verify(connectorStore, times(1)).putLong(storeKey.capture(), storeValue.capture());

    assertThat(storeKey.getValue())
        .isEqualTo("last-refreshed-issue-id")
        .as("Last refreshed issue key is as configured");
    assertThat(storeValue.getValue())
        .isEqualTo(0L)
        .as("Last refreshed ID should be reset to zero so that next batch will start over");
  }

  @Test
  void refreshIssues_upsert_error() throws IOException {
    when(jiraDao.findIssuesOrderedById(anyLong(), anyInt(), any()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomIssue(), randomDataGenerator.randomIssue()));

    doThrow(new IOException())
        .when(apiClient).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.refreshIssues(10)).isInstanceOf(RuntimeException.class);
    verify(apiClient, times(1)).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void refreshIssues_new_issues_found() throws IOException {
    final Issue issue1 = randomDataGenerator.randomIssue();
    final Issue issue2 = randomDataGenerator.randomIssue();

    when(connectorStore.getLong(anyString())).thenReturn(Optional.empty());

    ArgumentCaptor<Integer> batchSize = ArgumentCaptor.forClass(Integer.class);
    when(jiraDao.findIssuesOrderedById(anyLong(), batchSize.capture(), any()))
        .thenReturn(ImmutableList.of(issue1, issue2))
        .thenReturn(ImmutableList.of());

    connector.refreshIssues(10);

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClient, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .containsExactly(
            new UpsertTagRequest()
                .name(issue1.getProjectKey() + "-" + issue1.getIssueNumber())
                .description(issue1.getSummary())
                .additionalKeywords(ImmutableList.of(issue1.getProjectKey() + "-" + issue1.getIssueNumber()))
                .externalId(issue1.getId() + "")
                .path("/test/path/")
                .metadata(ImmutableMap.of(
                    "Project", issue1.getProjectKey(),
                    "Type", issue1.getIssueType()
                )),
            new UpsertTagRequest()
                .name(issue2.getProjectKey() + "-" + issue2.getIssueNumber())
                .description(issue2.getSummary())
                .additionalKeywords(ImmutableList.of(issue2.getProjectKey() + "-" + issue2.getIssueNumber()))
                .externalId(issue2.getId() + "")
                .path("/test/path/")
                .metadata(ImmutableMap.of(
                    "Project", issue2.getProjectKey(),
                    "Type", issue2.getIssueType()
                ))
        )
        .as("We should refresh tags for both issues found, with the configured tag upsert path");

    ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> storeValue = ArgumentCaptor.forClass(Long.class);
    verify(connectorStore, times(1)).putLong(storeKey.capture(), storeValue.capture());

    assertThat(batchSize.getValue())
        .isEqualTo(10)
        .as("The requested batch size should be used");
    assertThat(storeKey.getValue())
        .isEqualTo("last-refreshed-issue-id")
        .as("Last refreshed issue key is as configured");
    assertThat(storeValue.getValue())
        .isEqualTo(issue2.getId())
        .as("Last refreshed ID saved is from the last item in the issues list");
  }

  @Test
  void tagRefreshBatchSize_enforce_min() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "100");
    when(jiraDao.issueCount(anyString())).thenReturn(20L);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was less than the minimum refresh batch size")
        .isEqualTo(10);
  }

  @Test
  void tagRefreshBatchSize_enforce_max() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "20");
    when(jiraDao.issueCount(anyString())).thenReturn(10_000_000L);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was more than the maximum refresh batch size")
        .isEqualTo(20);
  }

  @Test
  void tagRefreshBatchSize_calculated() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "1000");
    final int fourteenDaysInMinutes = 20_160;
    when(jiraDao.issueCount(anyString())).thenReturn(400_000L);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was greater than the minimum and less than the maximum")
        .isEqualTo(400_000 / (fourteenDaysInMinutes / connectorModule.getIntervalConfig().getTagSlowLoopIntervalMinutes()));
  }

}
