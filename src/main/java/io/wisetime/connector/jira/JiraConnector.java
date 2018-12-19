/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;
import static io.wisetime.connector.utils.ActivityTimeCalculator.startTime;
import static io.wisetime.connector.utils.TagDurationCalculator.tagDuration;

/**
 * WiseTime Connector implementation for Jira.
 *
 * @author shane.xie@practiceinsight.io
 */
public class JiraConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(WiseTimeConnector.class);
  private static final String LAST_SYNCED_ISSUE_KEY = "last-synced-issue-id";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  @Inject
  private JiraDao jiraDao;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(jiraDao.hasExpectedSchema(),
        "Jira Database schema is unsupported by this connector");

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

      final List<Issue> issues = jiraDao.findIssuesOrderedById(
          lastPreviouslySyncedIssueId.orElse(0L),
          tagUpsertBatchSize(),
          getProjectKeys()
      );

      if (issues.isEmpty()) {
        return;
      } else {
        try {
          final List<UpsertTagRequest> upsertRequests = issues
              .stream()
              .map(i -> i.toUpsertTagRequest(tagUpsertPath()))
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
   * Updates the relevant issue and creates a Jira Worklog entry for it.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup userPostedTime) {
    Optional<String> callerKey = callerKey();
    if (callerKey.isPresent() && !callerKey.get().equals(userPostedTime.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("Invalid caller key in post time webhook call");
    }

    if (userPostedTime.getTags().isEmpty()) {
      return PostResult.SUCCESS
          .withMessage("Time group has no tags. There is nothing to post to Jira.");
    }

    final Optional<LocalDateTime> activityStartTime = startTime(userPostedTime);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> author = jiraDao.findUsername(userPostedTime.getUser().getExternalId());
    if (!author.isPresent()) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("User does not exist in Jira");
    }

    final Function<Tag, Optional<Issue>> findIssue = tag -> {
      final Optional<Issue> issue = jiraDao.findIssueByTagName(tag.getName());
      if (!issue.isPresent()) {
        log.warn("Can't find Jira issue for tag {}. No time will be posted for this tag.", tag.getName());
      }
      return issue;
    };

    final long workedTime = Math.round(tagDuration(userPostedTime));

    final Function<Issue, Issue> updateIssueTimeSpent = issue -> {
      final long updatedTimeSpent = issue.getTimeSpent() + workedTime;
      jiraDao.updateIssueTimeSpent(issue.getId(), updatedTimeSpent);
      return issue;
    };

    final Function<Issue, Issue> createWorklog = forIssue -> {
      final Worklog worklog = Worklog
          .builder()
          .issueId(forIssue.getId())
          .author(author.get())
          .body(templateFormatter.format(userPostedTime))
          .created(activityStartTime.get())
          .timeWorked(workedTime)
          .build();

      jiraDao.createWorklog(worklog);
      return forIssue;
    };

    try {
      jiraDao.asTransaction(() ->
          userPostedTime
              .getTags()
              .stream()

              .map(findIssue)
              .filter(Optional::isPresent)
              .map(Optional::get)

              .map(updateIssueTimeSpent)
              .map(createWorklog)

              .forEach(issue ->
                  log.info("Posted time to Jira issue {} on behalf of {}", issue.getKey(), author.get())
              )
      );
    } catch (RuntimeException e) {
      return PostResult.TRANSIENT_FAILURE
          .withError(e)
          .withMessage("There was an error posting time to the Jira database");
    }
    return PostResult.SUCCESS;
  }

  @Override
  public boolean isConnectorHealthy() {
    return jiraDao.hasConfiguredTimeZone();
  }

  private int tagUpsertBatchSize() {
    return RuntimeConfig
        .getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE)
        // A large batch mitigates query round trip latency
        .orElse(500);
  }

  private String tagUpsertPath() {
    return RuntimeConfig
        .getString(JiraConnectorConfigKey.TAG_UPSERT_PATH)
        .orElse("/Jira/");
  }

  private Optional<String> callerKey() {
    return RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY);
  }

  /**
   * @return an array of projects keys used in upserting tags
   */
  @VisibleForTesting
  String[] getProjectKeys() {
    return RuntimeConfig
        .getString(JiraConnectorConfigKey.PROJECT_KEYS_FILTER)
        .map(keys ->
            Arrays.stream(keys.split("\\s*,\\s*"))
                .map(String::trim)
                .toArray(String[]::new)
        ).orElse(ArrayUtils.toArray());
  }
}
