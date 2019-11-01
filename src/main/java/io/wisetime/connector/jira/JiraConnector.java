/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import com.vdurmont.emoji.EmojiParser;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.WiseTimeConnector;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;
import io.wisetime.connector.utils.DurationCalculator;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import io.wisetime.generated.connect.User;
import spark.Request;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;
import static io.wisetime.connector.utils.ActivityTimeCalculator.startTime;

/**
 * WiseTime Connector implementation for Jira.
 *
 * @author shane.xie
 */
public class JiraConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(WiseTimeConnector.class);
  private static final String LAST_SYNCED_ISSUE_KEY = "last-synced-issue-id";
  private static final String LAST_REFRESHED_ISSUE_KEY = "last-refreshed-issue-id";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  @Inject
  private JiraDao jiraDao;

  @Override
  public String getConnectorType() {
    return "wisetime-jira-connector";
  }

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(jiraDao.hasExpectedSchema(),
        "Jira Database schema is unsupported by this connector");

    this.apiClient = connectorModule.getApiClient();
    this.connectorStore = connectorModule.getConnectorStore();
    templateFormatter = new TemplateFormatter(
        TemplateFormatterConfig.builder()
            .withTemplatePath("classpath:jira-template.ftl")
            .build()
    );
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   *
   *   1. Finds all Jira issues that haven't been synced and creates matching tags for them in WiseTime.
   *      Blocks until all issues have been synced.
   *
   *   2. Sends a batch of already synced issues to WiseTime to maintain freshness of existing tags.
   *      Mitigates effect of renamed or missed tags. Only one refresh batch per performTagUpdate() call.
   */
  @Override
  public void performTagUpdate() {
    syncNewIssues();
    refreshIssues(tagUpsertBatchSize());
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to our team. Updates the relevant issue and creates
   * a Jira Worklog entry for it.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup timeGroup) {
    log.info("Posted time received: {}", timeGroup.getGroupId());

    if (callerKey().isPresent() && !callerKey().get().equals(timeGroup.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("Invalid caller key in posted time webhook call");
    }

    if (timeGroup.getTags().isEmpty()) {
      return PostResult.SUCCESS()
          .withMessage("Time group has no tags. There is nothing to post to Jira.");
    }

    final Set<Tag> relevantTags = timeGroup.getTags().stream()
        .filter(createdByConnector)
        .filter(relevantProjectKey)
        .collect(Collectors.toSet());

    if (relevantTags.isEmpty()) {
      return PostResult.SUCCESS()
          .withMessage("Time group has no Jira tags or it contains tags that don't match the configured " +
              "project keys filter. There is nothing to post to Jira.");
    }

    final Optional<LocalDateTime> activityStartTime = startTime(timeGroup);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> author = findUser(timeGroup.getUser());
    if (!author.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("User does not exist in Jira");
    }

    final long workedTime = DurationCalculator
        .of(timeGroup)
        .calculate()
        .getPerTagDuration();

    try {
      jiraDao.asTransaction(() -> {
        relevantTags.stream()
            .forEach(tag -> {
              final Issue issue = jiraDao
                  .findIssueByTagName(tag.getName())
                  .orElseThrow(() -> new IssueNotFoundException("Can't find Jira issue for tag " + tag.getName()));

              jiraDao.updateIssueTimeSpent(issue.getId(), issue.getTimeSpent() + workedTime);
              final Worklog worklog = buildWorklog(issue, timeGroup, author.get(), activityStartTime.get(), workedTime);
              jiraDao.createWorklog(worklog);
              log.info("Posted time {} to Jira issue {}", timeGroup.getGroupId(), issue.getKey());
            });
      });
    } catch (IssueNotFoundException e) {
      log.warn("Can't post time to Jira: " + e.getMessage());
      return PostResult.PERMANENT_FAILURE()
          .withError(e)
          .withMessage(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("There was an error posting time to the Jira database", e);
      return PostResult.TRANSIENT_FAILURE()
          .withError(e)
          .withMessage("There was an error posting time to the Jira database");
    }
    return PostResult.SUCCESS();
  }

  @Override
  public boolean isConnectorHealthy() {
    return jiraDao.pingDb();
  }

  @Override
  public void shutdown() {
    jiraDao.shutdown();
  }

  /**
   * Drain all unsynced issues and send to WiseTime
   */
  @VisibleForTesting
  void syncNewIssues() {
    while (true) {
      final Optional<Long> lastPreviouslySyncedIssueId = connectorStore.getLong(LAST_SYNCED_ISSUE_KEY);

      final List<Issue> newIssues = jiraDao.findIssuesOrderedById(
          lastPreviouslySyncedIssueId.orElse(0L),
          tagUpsertBatchSize(),
          getProjectKeysFilter()
      );

      if (newIssues.isEmpty()) {
        log.info("No new tags found. Last issue ID synced: {}", lastPreviouslySyncedIssueId);
        return;
      }

      log.info("Detected {} new {}: {}",
          newIssues.size(),
          newIssues.size() > 1 ? "tags" : "tag",
          newIssues.stream().map(Issue::getKey).collect(Collectors.joining(", ")));

      upsertWiseTimeTags(newIssues);

      final long lastSyncedIssueId = newIssues.get(newIssues.size() - 1).getId();
      connectorStore.putLong(LAST_SYNCED_ISSUE_KEY, lastSyncedIssueId);
      log.info("Last synced issue ID: {}", lastSyncedIssueId);
    }
  }

  /**
   * Get one batch of issues and send to WiseTime to keep tags up to date
   */
  @VisibleForTesting
  void refreshIssues(final int batchSize) {
    final Optional<Long> lastPreviouslyRefreshedIssueId = connectorStore.getLong(LAST_REFRESHED_ISSUE_KEY);

    final List<Issue> refreshIssues = jiraDao.findIssuesOrderedById(
        lastPreviouslyRefreshedIssueId.orElse(0L),
        batchSize,
        getProjectKeysFilter()
    );

    if (refreshIssues.isEmpty()) {
      // Start over the next time we are called
      connectorStore.putLong(LAST_REFRESHED_ISSUE_KEY, 0L);
      return;
    }

    log.info("Refreshing {} {}: {}",
        refreshIssues.size(),
        refreshIssues.size() > 1 ? "tags" : "tag",
        refreshIssues.stream().map(Issue::getKey).collect(Collectors.joining(", ")));

    upsertWiseTimeTags(refreshIssues);

    final long lastRefreshedIssueId = refreshIssues.get(refreshIssues.size() - 1).getId();
    connectorStore.putLong(LAST_REFRESHED_ISSUE_KEY, lastRefreshedIssueId);
  }

  private void upsertWiseTimeTags(final List<Issue> issues) {
    try {
      final List<UpsertTagRequest> upsertRequests = issues
          .stream()
          .map(i -> i.toUpsertTagRequest(tagUpsertPath()))
          .collect(Collectors.toList());
      apiClient.tagUpsertBatch(upsertRequests);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private final Predicate<Tag> createdByConnector = tag ->
      tag.getPath().equals(tagUpsertPath() + tag.getName()) ||
          tag.getPath().equals(StringUtils.strip(tagUpsertPath(), "/"));  // Old, deprecated format

  private final Predicate<Tag> relevantProjectKey = tag -> {
    if (getProjectKeysFilter().length > 0) {
      return JiraDao.IssueKey
          .fromTagName(tag.getName())
          .filter(issueKey -> ArrayUtils.contains(getProjectKeysFilter(), issueKey.getProjectKey()))
          .isPresent();
    }
    return JiraDao.IssueKey
        .fromTagName(tag.getName())
        .isPresent();
  };

  private Optional<String> findUser(final User user) {
    if (StringUtils.isEmpty(user.getExternalId())) {
      return jiraDao.findUsernameByEmail(user.getEmail());
    }
    if (jiraDao.userExists(user.getExternalId())) {
      // This is the user's Jira username
      return Optional.of(user.getExternalId());
    }
    if (user.getExternalId().split("@").length == 2) {
      // Looks like an email
      return jiraDao.findUsernameByEmail(user.getExternalId());
    }
    return Optional.empty();
  }

  private Worklog buildWorklog(final Issue issue, final TimeGroup timeGroup,
                               final String author, final LocalDateTime startTime, final long workedTime) {
    final String messageBody = StringUtils.trimToEmpty(
        EmojiParser.removeAllEmojis(
            templateFormatter.format(timeGroup)
        )
    );
    return Worklog
        .builder()
        .issueId(issue.getId())
        .author(author)
        .body(messageBody)
        .created(startTime)
        .timeWorked(workedTime)
        .build();
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
   * If configured, the connector will only handle the project keys returned by this method.
   * Otherwise the connector will handle all Jira projects.
   */
  @VisibleForTesting
  String[] getProjectKeysFilter() {
    return RuntimeConfig
        .getString(JiraConnectorConfigKey.PROJECT_KEYS_FILTER)
        .map(keys ->
            Arrays.stream(keys.split("\\s*,\\s*"))
                .map(String::trim)
                .toArray(String[]::new)
        ).orElse(ArrayUtils.toArray());
  }

  private static class IssueNotFoundException extends RuntimeException {
    IssueNotFoundException(String message) {
      super(message);
    }
  }
}
