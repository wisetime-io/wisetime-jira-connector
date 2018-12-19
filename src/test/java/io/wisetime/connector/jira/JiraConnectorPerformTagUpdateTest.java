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

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.UpsertTagRequest;

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

/**
 * @author shane.xie#practiceinsight.io
 */
class JiraConnectorPerformTagUpdateTest {

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static JiraDao jiraDao = mock(JiraDao.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static ConnectorStore connectorStore = mock(ConnectorStore.class);
  private static JiraConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, String.valueOf(100));
    RuntimeConfig.setProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH, "/test/path/");
    RuntimeConfig.setProperty(JiraConnectorConfigKey.PROJECT_KEYS_FILTER, "WT, IPFLOW");
    RuntimeConfig.clearProperty(ConnectorConfigKey.CALLER_KEY);

    assertThat(RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY))
        .as("CALLER_KEY empty value expected")
        .isNotPresent();

    assertThat(RuntimeConfig.getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE))
        .as("TAG_UPSERT_BATCH_SIZE should be set to 100")
        .contains(100);

    assertThat(RuntimeConfig.getString(JiraConnectorConfigKey.PROJECT_KEYS_FILTER))
        .as("ONLY_UPSERT_TAGS_FOR_PROJECT_KEYS should contain WT, IPFLOW")
        .contains("WT, IPFLOW");

    connector = Guice.createInjector(binder -> {
      binder.bind(JiraDao.class).toProvider(() -> jiraDao);
    }).getInstance(JiraConnector.class);

    // Ensure JiraConnector#init will not fail
    doReturn(true).when(jiraDao).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClient, mock(TemplateFormatter.class), connectorStore));
  }

  @SuppressWarnings("Duplicates")
  @AfterAll
  static void tearDown() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.TAG_UPSERT_PATH);
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.PROJECT_KEYS_FILTER);

    assertThat(RuntimeConfig.getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE))
        .as("TAG_UPSERT_BATCH_SIZE empty result expected")
        .isNotPresent();
    assertThat(RuntimeConfig.getString(JiraConnectorConfigKey.TAG_UPSERT_PATH))
        .isNotPresent();
    assertThat(RuntimeConfig.getString(JiraConnectorConfigKey.PROJECT_KEYS_FILTER))
        .isNotPresent();
  }

  @BeforeEach
  void setUpTest() {
    reset(jiraDao);
    reset(apiClient);
    reset(connectorStore);
  }

  @Test
  void performTagUpdate_no_jira_issues_found() throws IOException {
    when(jiraDao.findIssuesOrderedById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    verify(apiClient, never()).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_upsert_error() throws IOException {
    when(jiraDao.findIssuesOrderedById(anyLong(), anyInt(), any()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomIssue(), randomDataGenerator.randomIssue()));

    doThrow(new IOException())
        .when(apiClient).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.performTagUpdate()).isInstanceOf(RuntimeException.class);
    verify(apiClient, times(1)).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_new_issues_found() throws IOException {
    final Issue issue1 = randomDataGenerator.randomIssue();
    final Issue issue2 = randomDataGenerator.randomIssue();

    when(connectorStore.getLong(anyString())).thenReturn(Optional.empty());

    ArgumentCaptor<Integer> batchSize = ArgumentCaptor.forClass(Integer.class);
    when(jiraDao.findIssuesOrderedById(anyLong(), batchSize.capture(), any()))
        .thenReturn(ImmutableList.of(issue1, issue2))
        .thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClient, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .containsExactly(
            new UpsertTagRequest()
                .name(issue1.getProjectKey() + "-" + issue1.getIssueNumber())
                .description(issue1.getSummary())
                .additionalKeywords(ImmutableList.of(issue1.getProjectKey() + "-" + issue1.getIssueNumber()))
                .path("/test/path/"),
            new UpsertTagRequest()
                .name(issue2.getProjectKey() + "-" + issue2.getIssueNumber())
                .description(issue2.getSummary())
                .additionalKeywords(ImmutableList.of(issue2.getProjectKey() + "-" + issue2.getIssueNumber()))
                .path("/test/path/")
        )
        .as("We should create tags for both new issues found, with the configured tag upsert path");

    ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> storeValue = ArgumentCaptor.forClass(Long.class);
    verify(connectorStore, times(1)).putLong(storeKey.capture(), storeValue.capture());

    assertThat(batchSize.getValue())
        .isEqualTo(100)
        .as("The configured batch size should be used");
    assertThat(storeKey.getValue())
        .isEqualTo("last-synced-issue-id")
        .as("Last synced issue key is as configured");
    assertThat(storeValue.getValue())
        .isEqualTo(issue2.getId())
        .as("Last synced ID saved is from the last item in the issues list");
  }

  @Test
  void getProjectKeys_some_configured() {
    String[] projectKeys = connector.getProjectKeysFilter();
    assertThat(projectKeys).isNotEmpty();
    assertThat(projectKeys).contains("WT", "IPFLOW");
  }

  @Test
  void getProjectKeys_none_configured() {
    RuntimeConfig.clearProperty(JiraConnectorConfigKey.PROJECT_KEYS_FILTER);
    String[] projectKeys = connector.getProjectKeysFilter();
    assertThat(projectKeys).isEmpty();
  }
}
