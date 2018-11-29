/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.connector.jira.database.JiraDb;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

/**
 * WiseTime Connector implementation for Jira.
 *
 * @author shane.xie@practiceinsight.io
 */
public class JiraConnector implements WiseTimeConnector {

  private static String LAST_SYNCED_ISSUE_KEY = "last-synced-issue-id";
  private static String ABSOLUTE_TAG_PATH = "/Jira";
  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  @Inject
  private JiraDb jiraDb;

  @Override
  public void init(ConnectorModule connectorModule) {
    this.apiClient = connectorModule.getApiClient();
    this.connectorStore = connectorModule.getConnectorStore();
    this.templateFormatter = connectorModule.getTemplateFormatter();
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   * Finds Jira issues that haven't been synced and creates matching tags for them in WiseTime.
   */
  @Override
  public void performTagUpdate() {
    while (true) {
      final Optional<Long> lastPreviouslySyncedIssueId = connectorStore.getLong(LAST_SYNCED_ISSUE_KEY);
      final int maxBatchSize = 500;  // A large batch mitigates query round trip latency

      final List<Issue> issues = jiraDb
          .findIssuesOrderedById(lastPreviouslySyncedIssueId.orElse(0L), maxBatchSize);

      if (issues.size() == 0) {
        return;
      } else {
        try {
          final List<UpsertTagRequest> upsertRequests = issues
              .stream()
              .map(i -> i.toUpsertTagRequest(ABSOLUTE_TAG_PATH))
              .collect(Collectors.toList());

          apiClient.tagUpsertBatch(upsertRequests);

          final long lastSyncedIssueId = issues.get(issues.size() - 1).getId();
          connectorStore.putLong(LAST_SYNCED_ISSUE_KEY, lastSyncedIssueId);

        } catch (IOException e) {
          // The batch will be retried since we didn't update the last synced issue ID
          // Let scheduler know that this batch has failed
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to our team.
   */
  @Override
  public PostResult postTime(Request request, TimeGroup userPostedTime) {
    // TODO
    return PostResult.SUCCESS;
  }

  @Override
  public boolean isConnectorHealthy() {
    return jiraDb.canUseDatabase();
  }
}
