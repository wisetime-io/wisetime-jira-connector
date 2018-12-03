/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.jira.database.JiraDb;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.testutils.FakeEntities;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.UpsertTagRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author shane.xie#practiceinsight.io
 */
class JiraConnectorPerformTagUpdateTest {

  private static FakeEntities fakeEntities = new FakeEntities();
  private static JiraDb jiraDb = mock(JiraDb.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static ConnectorStore connectorStore = mock(ConnectorStore.class);
  private static JiraConnector connector;

  @BeforeAll
  static void setUp() {
    connector = Guice.createInjector(binder -> {
      binder.bind(JiraDb.class).toProvider(() -> jiraDb);
    }).getInstance(JiraConnector.class);

    connector.init(new ConnectorModule(apiClient, mock(TemplateFormatter.class), connectorStore));
  }

  @BeforeEach
  void setUpTest() {
    reset(jiraDb);
    reset(apiClient);
    reset(connectorStore);
  }

  @Test
  void performTagUpdate_no_jira_issues_found() throws IOException {
    when(jiraDb.findIssuesOrderedById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    verify(apiClient, never()).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_upsert_error() throws IOException {
    when(jiraDb.findIssuesOrderedById(anyLong(), anyInt()))
        .thenReturn(ImmutableList.of(fakeEntities.randomIssue(), fakeEntities.randomIssue()));

    doThrow(new IOException())
        .when(apiClient).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.performTagUpdate()).isInstanceOf(RuntimeException.class);
    verify(apiClient, times(1)).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_new_issues_found() throws IOException {
    final Issue issue1 = fakeEntities.randomIssue();
    final Issue issue2 = fakeEntities.randomIssue();

    when(connectorStore.getLong(anyString())).thenReturn(Optional.empty());

    when(jiraDb.findIssuesOrderedById(anyLong(), anyInt()))
        .thenReturn(ImmutableList.of(issue1, issue2))
        .thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    ArgumentCaptor<List> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClient, only()).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .containsExactly(
            new UpsertTagRequest()
                .name(issue1.getProjectKey() + "-" + issue1.getIssueNumber())
                .description(issue1.getSummary())
                .additionalKeywords(ImmutableList.of(issue1.getProjectKey() + "-" + issue1.getIssueNumber()))
                .path("/Jira"),
            new UpsertTagRequest()
                .name(issue2.getProjectKey() + "-" + issue2.getIssueNumber())
                .description(issue2.getSummary())
                .additionalKeywords(ImmutableList.of(issue2.getProjectKey() + "-" + issue2.getIssueNumber()))
                .path("/Jira")
        )
        .as("We should create tags for both new issues found");

    ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> storeValue = ArgumentCaptor.forClass(Long.class);
    verify(connectorStore, times(1)).putLong(storeKey.capture(), storeValue.capture());

    assertThat(storeKey.getValue())
        .isEqualTo("last-synced-issue-id")
        .as("Last synced issue key is as configured");
    assertThat(storeValue.getValue())
        .isEqualTo(issue2.getId())
        .as("Last synced ID saved is from the last item in the issues list");
  }
}