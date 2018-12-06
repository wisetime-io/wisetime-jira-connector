/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.connector.jira.config.CallerKey;
import io.wisetime.connector.jira.config.TagUpsertBatchSize;
import io.wisetime.connector.jira.config.TagUpsertPath;
import io.wisetime.connector.jira.database.JiraDb;
import io.wisetime.connector.jira.models.ImmutableWorklog;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

import static io.wisetime.connector.jira.utils.ActivityTimeCalculator.timeGroupStartHour;
import static io.wisetime.connector.jira.utils.TagDurationCalculator.tagDurationSecs;

/**
 * WiseTime Connector implementation for Jira.
 *
 * @author shane.xie@practiceinsight.io
 */
public class JiraConnector implements WiseTimeConnector {

  private static String LAST_SYNCED_ISSUE_KEY = "last-synced-issue-id";
  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  @Inject
  private JiraDb jiraDb;

  @Inject
  @TagUpsertPath
  private String tagUpsertPath;

  @Inject
  @TagUpsertBatchSize
  private int tagUpsertBatchSize;

  @Inject
  @CallerKey
  private Optional<String> callerKey;

  @Override
  public void init(ConnectorModule connectorModule) {
    Preconditions.checkArgument(jiraDb.hasExpectedSchema(), "DB Schema of connected Jira is not yet supported.");

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

      final List<Issue> issues = jiraDb.findIssuesOrderedById(
          lastPreviouslySyncedIssueId.orElse(0L),
          tagUpsertBatchSize
      );

      if (issues.size() == 0) {
        return;
      } else {
        try {
          final List<UpsertTagRequest> upsertRequests = issues
              .stream()
              .map(i -> i.toUpsertTagRequest(tagUpsertPath))
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
   * Creates a Jira Worklog entry for the relevant issue.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup userPostedTime) {

    if (callerKey.isPresent() && !callerKey.get().equals(userPostedTime.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("Invalid caller key in post time webhook call");
    }

    if (userPostedTime.getTags().size() == 0) {
      // Nothing to do
      return PostResult.SUCCESS
          .withMessage("Time group has no tags. There is nothing to post to Jira.");
    }

    final Optional<LocalDateTime> activityStartTime = timeGroupStartHour(userPostedTime);
    if (!activityStartTime.isPresent()) {
      // Invalid group with no time rows
      return PostResult.PERMANENT_FAILURE
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> author = jiraDb.findUsername(userPostedTime.getUser().getExternalId());
    if (!author.isPresent()) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("User does not exist in Jira");
    }

    final String worklogBody = templateFormatter.format(userPostedTime);
    final long workedTime = Math.round(tagDurationSecs(userPostedTime));

    try {
      jiraDb.asTransaction(() -> {
        userPostedTime
            .getTags()
            .stream()

            // Find matching Jira issue
            .map(Tag::getName)
            .map(jiraDb::findIssueByTagName)

            // Fail the transaction if one of the tags doesn't have a matching issue
            .map(Optional::get)

            // Update issue time spent
            .map(issue -> {
              final long updatedTimeSpent = issue.getTimeSpent() + workedTime;
              jiraDb.updateIssueTimeSpent(issue.getId(), updatedTimeSpent);
              return issue;
            })

            // Create worklog entry for issue
            .forEach(issue -> {
              final Worklog worklog = ImmutableWorklog
                  .builder()
                  .issueId(issue.getId())
                  .author(author.get())
                  .body(worklogBody)
                  .created(activityStartTime.get())
                  .timeWorked(workedTime)
                  .build();

              jiraDb.createWorklog(worklog);
            });
      });
    } catch (RuntimeException e) {
      return PostResult.TRANSIENT_FAILURE
          .withError(e)
          .withMessage("There was an error posting time to the Jira database");
    }
    return PostResult.SUCCESS;
  }

  @Override
  public boolean isConnectorHealthy() {
    return jiraDb.canQueryDatabase();
  }

  @VisibleForTesting
  public void setCallerKey(final String callerKey) {
    this.callerKey = Optional.of(callerKey);
  }
}
