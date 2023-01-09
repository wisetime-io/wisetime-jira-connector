/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;
import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.vdurmont.emoji.EmojiParser;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.WiseTimeConnector;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;
import io.wisetime.connector.utils.ActivityTimeCalculator;
import io.wisetime.connector.utils.DurationCalculator;
import io.wisetime.generated.connect.DeleteTagRequest;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import io.wisetime.generated.connect.User;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WiseTime Connector implementation for Jira.
 *
 * @author shane.xie
 */
public class JiraConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(WiseTimeConnector.class);
  private static final String LAST_SYNCED_ISSUE_KEY = "last-synced-issue-id";
  private static final String LAST_REFRESHED_ISSUE_KEY = "last-refreshed-issue-id";

  private int tagSyncIntervalMinutes;
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

    tagSyncIntervalMinutes = connectorModule.getIntervalConfig().getTagSlowLoopIntervalMinutes();
    apiClient = connectorModule.getApiClient();
    connectorStore = connectorModule.getConnectorStore();
    templateFormatter = new TemplateFormatter(
        TemplateFormatterConfig.builder()
            .withTemplatePath("classpath:jira-template.ftl")
            .build()
    );
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   *
   * Finds all Jira issues that haven't been synced and creates matching tags for them in WiseTime.
   * Blocks until all issues have been synced.
   */
  @Override
  public void performTagUpdate() {
    syncNewIssues();
  }

  /**
   * Sends a batch of already synced issues to WiseTime to maintain freshness of existing tags.
   * Mitigates effect of renamed or missed tags.
   */
  @Override
  public void performTagUpdateSlowLoop() {
    refreshIssues(tagRefreshBatchSize());
  }

  @Override
  public void performActivityTypeUpdate() {
    // Activity type update is not performed in this connector
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to our team. Updates the relevant issue and creates
   * a Jira Worklog entry for it.
   */
  @Override
  public PostResult postTime(final TimeGroup timeGroup) {
    log.info("Posted time received: {}", timeGroup.getGroupId());

    if (timeGroup.getTags().isEmpty()) {
      return PostResult.SUCCESS()
          .withMessage("Time group has no tags. There is nothing to post to Jira.");
    }

    final Set<Tag> relevantTags = timeGroup.getTags().stream()
        .filter(createdByConnector)
        .filter(relevantProjectKey)
        .collect(Collectors.toSet());

    if (relevantTags.isEmpty()) {
      final String tagsDescription = timeGroup.getTags().stream()
          .map(Tag::getName).collect(Collectors.joining(", "));
      return PostResult.SUCCESS()
          .withMessage("There is nothing to post to Jira. The time group has no Jira tags or it contains tags that don't "
              + "match the configured project keys filter. Tags were: " + tagsDescription);
    }

    final Optional<Instant> activityStartTime = ActivityTimeCalculator.startInstant(timeGroup);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> author = findUser(timeGroup.getUser());
    if (!author.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("User does not exist in Jira");
    }

    timeGroup.tags(Lists.newArrayList(relevantTags));
    final long workedTime = DurationCalculator
        .of(timeGroup)
        .roundToNearestSeconds(1) // no rounding
        .calculate();

    try {
      List<Tag> tagsMissingInJira = new ArrayList<>();
      List<Issue> issuesToPostTo = relevantTags.stream()
          .map(tag -> {
            final Optional<Issue> issue = jiraDao.findIssueByTagName(tag.getName());
            if (!issue.isPresent()) {
              tagsMissingInJira.add(tag);
            }
            return issue;
          })
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(Collectors.toList());

      if (!tagsMissingInJira.isEmpty()) {
        log.warn("Couldn't find all tags in Jira");
        tagsMissingInJira.forEach(tag -> {
          try {
            // the tag will be deleted, but the user still needs to manually repost and existing time rows need
            // to be fixed
            apiClient.tagDelete(new DeleteTagRequest().name(tag.getName()));
          } catch (IOException e) {
            log.error("Error deleting tag: {}", tag.toString(), e);
            // connect-api-server down: Throw general exception to retry
            throw new RuntimeException(e);
          }
        });
        throw new IssueNotFoundException("Jira issue was not found for next tags: "
            + tagsMissingInJira.stream()
            .map(Tag::getName)
            .collect(Collectors.joining(", ")));
      }
      jiraDao.asTransaction(() -> {
        issuesToPostTo.forEach(issue -> {
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
      final long lastPreviouslySyncedIssueId = connectorStore.getLong(LAST_SYNCED_ISSUE_KEY).orElse(0L);

      final List<Issue> newIssues = jiraDao.findIssuesOrderedById(
          lastPreviouslySyncedIssueId,
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
          ellipsize(newIssues.stream().map(Issue::getKey).collect(Collectors.toList())));

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
    final long lastPreviouslyRefreshedIssueId = connectorStore.getLong(LAST_REFRESHED_ISSUE_KEY).orElse(0L);

    final List<Issue> refreshIssues = jiraDao.findIssuesOrderedById(
        lastPreviouslyRefreshedIssueId,
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
        ellipsize(refreshIssues.stream().map(Issue::getKey).collect(Collectors.toList())));

    upsertWiseTimeTags(refreshIssues);

    final long lastRefreshedIssueId = refreshIssues.get(refreshIssues.size() - 1).getId();
    connectorStore.putLong(LAST_REFRESHED_ISSUE_KEY, lastRefreshedIssueId);
  }

  private void upsertWiseTimeTags(final List<Issue> issues) {
    try {
      final List<UpsertTagRequest> upsertRequests = issues
          .stream()
          .map(issue -> {
            UpsertTagRequest request = issue.toUpsertTagRequest(tagUpsertPath());
            RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_ISSUE_URL_PREFIX)
                .ifPresent(urlPrefix -> request.url(urlPrefix + issue.getKey()));
            return request;
          })
          .collect(Collectors.toList());
      apiClient.tagUpsertBatch(upsertRequests);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private final Predicate<Tag> createdByConnector = tag ->
      tag.getPath().equals(tagUpsertPath())
          || tag.getPath().equals(StringUtils.strip(tagUpsertPath(), "/"));  // Old, deprecated format

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
                               final String author, final Instant startTime, final long workedTime) {
    final String messageBody = StringUtils.trimToEmpty(
        EmojiParser.removeAllEmojis(
            templateFormatter.format(timeGroup)
        )
    );
    return new Worklog()
        .setIssueId(issue.getId())
        .setAuthor(author)
        .setBody(messageBody)
        .setCreated(startTime)
        .setTimeWorked(workedTime);
  }

  private int tagUpsertBatchSize() {
    return RuntimeConfig
        .getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE)
        // A large batch mitigates query round trip latency
        .orElse(200);
  }

  @VisibleForTesting
  int tagRefreshBatchSize() {
    final long tagCount = jiraDao.issueCount(getProjectKeysFilter());
    final long batchFullFortnightlyRefresh = tagCount / (TimeUnit.DAYS.toMinutes(14) / tagSyncIntervalMinutes);

    if (batchFullFortnightlyRefresh > tagUpsertBatchSize()) {
      return tagUpsertBatchSize();
    }
    final int minimumBatchSize = 10;
    if (batchFullFortnightlyRefresh < minimumBatchSize) {
      return minimumBatchSize;
    }
    return (int) batchFullFortnightlyRefresh;
  }

  private String tagUpsertPath() {
    return RuntimeConfig
        .getString(JiraConnectorConfigKey.TAG_UPSERT_PATH)
        .orElse("/Jira/");
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

  private static String ellipsize(final List<String> items) {
    if (items.size() < 6) {
      return String.join(", ", items);
    }
    return items.get(0) + ", ... , " + items.get(items.size() - 1);
  }

  private static class IssueNotFoundException extends RuntimeException {
    IssueNotFoundException(String message) {
      super(message);
    }
  }
}
