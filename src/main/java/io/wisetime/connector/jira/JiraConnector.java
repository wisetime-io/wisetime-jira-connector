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
import java.util.function.Function;
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
    templateFormatter = new TemplateFormatter(
        TemplateFormatterConfig.builder()
            .withTemplatePath("classpath:jira-template.ftl")
            .build()
    );
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule. Finds Jira issues that haven't been synced and creates
   * matching tags for them in WiseTime.
   */
  @Override
  public void performTagUpdate() {
    while (true) {
      final Optional<Long> lastPreviouslySyncedIssueId = connectorStore.getLong(LAST_SYNCED_ISSUE_KEY);

      final List<Issue> issues = jiraDao.findIssuesOrderedById(
          lastPreviouslySyncedIssueId.orElse(0L),
          tagUpsertBatchSize(),
          getProjectKeysFilter()
      );

      if (issues.isEmpty()) {
        log.info("No new tags found. Last issue ID synced: {}", lastPreviouslySyncedIssueId);
        return;
      } else {
        try {
          log.info("Detected {} new {}: {}",
              issues.size(),
              issues.size() > 1 ? "tags" : "tag",
              issues.stream().map(Issue::getKey).collect(Collectors.joining(", ")));

          final List<UpsertTagRequest> upsertRequests = issues
              .stream()
              .map(i -> i.toUpsertTagRequest(tagUpsertPath()))
              .collect(Collectors.toList());

          apiClient.tagUpsertBatch(upsertRequests);

          final long lastSyncedIssueId = issues.get(issues.size() - 1).getId();
          connectorStore.putLong(LAST_SYNCED_ISSUE_KEY, lastSyncedIssueId);
          log.info("Last synced issue ID: {}", lastSyncedIssueId);
        } catch (IOException e) {
          // The batch will be retried since we didn't update the last synced issue ID
          // Let scheduler know that this batch has failed
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to our team. Updates the relevant issue and creates
   * a Jira Worklog entry for it.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup timeGroup) {
    log.info("Posted time received: {}", timeGroup.getGroupId());

    Optional<String> callerKey = callerKey();
    if (callerKey.isPresent() && !callerKey.get().equals(timeGroup.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("Invalid caller key in post time webhook call");
    }

    if (timeGroup.getTags().isEmpty()) {
      return PostResult.SUCCESS()
          .withMessage("Time group has no tags. There is nothing to post to Jira.");
    }

    final Predicate<Tag> relevantProjectKey = tag -> {
      if (getProjectKeysFilter().length == 0) {
        // filter our not jira specific tags early
        return JiraDao.IssueKey
            .fromTagName(tag.getName())
            .isPresent();
      }
      return JiraDao.IssueKey
          .fromTagName(tag.getName())
          .filter(issueKey -> ArrayUtils.contains(getProjectKeysFilter(), issueKey.getProjectKey()))
          .isPresent();
    };

    final List<Tag> relevantTags = timeGroup.getTags().stream().filter(relevantProjectKey).collect(Collectors.toList());
    if (relevantTags.isEmpty()) {
      return PostResult.SUCCESS()
          .withMessage("Time group has no Jira tags or tags matching specified project keys filter. "
              + "There is nothing to post to Jira.");
    }

    final Optional<LocalDateTime> activityStartTime = startTime(timeGroup);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> author = getJiraUser(timeGroup.getUser());
    if (!author.isPresent()) {
      return PostResult.PERMANENT_FAILURE()
          .withMessage("User does not exist in Jira");
    }

    final Function<Tag, Optional<Issue>> findIssue = tag -> {
      final Optional<Issue> issue = jiraDao.findIssueByTagName(tag.getName());
      if (!issue.isPresent()) {
        log.warn("Can't find Jira issue for tag {}. No time will be posted for this tag.", tag.getName());
      }
      return issue;
    };

    final long workedTime = DurationCalculator
        .of(timeGroup)
        .calculate()
        .getPerTagDuration();

    final Function<Issue, Issue> updateIssueTimeSpent = issue -> {
      final long updatedTimeSpent = issue.getTimeSpent() + workedTime;
      jiraDao.updateIssueTimeSpent(issue.getId(), updatedTimeSpent);
      return issue;
    };

    final Function<Issue, Issue> createWorklog = forIssue -> {
      final String messageBody = trimToUtf8(templateFormatter.format(timeGroup));

      final Worklog worklog = Worklog
          .builder()
          .issueId(forIssue.getId())
          .author(author.get())
          .body(messageBody)
          .created(activityStartTime.get())
          .timeWorked(workedTime)
          .build();

      jiraDao.createWorklog(worklog);
      return forIssue;
    };

    try {
      jiraDao.asTransaction(() -> {
        final List<Issue> postedIssues = relevantTags
            .stream()
            .map(findIssue)
            .filter(Optional::isPresent)
            .map(Optional::get)

            .map(updateIssueTimeSpent)
            .map(createWorklog)
            .collect(Collectors.toList());

        postedIssues
            .forEach(issue -> log.info("Posted time {} to Jira issue {}", timeGroup.getGroupId(), issue.getKey()));
      });
    } catch (RuntimeException e) {
      log.warn("There was an error posting time to the Jira database", e);
      return PostResult.TRANSIENT_FAILURE()
          .withError(e)
          .withMessage("There was an error posting time to the Jira database");
    }
    return PostResult.SUCCESS();
  }

  /**
   * Returns trimmed string, where characters that supported jira databases cannot support, such as emoji characters.
   */
  String trimToUtf8(String messageBody) {
    return StringUtils.trimToEmpty(EmojiParser.removeAllEmojis(messageBody));
  }

  @Override
  public boolean isConnectorHealthy() {
    return jiraDao.pingDb();
  }

  @Override
  public String getConnectorType() {
    return "wisetime-jira-connector";
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
   * If configured, the connector will only handle the project keys returned by this method. If project keys filter is
   * not configured, the connector will handle all Jira projects.
   *
   * @return array of projects keys
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

  private Optional<String> getJiraUser(User user) {
    if (StringUtils.isNotBlank(user.getExternalId())) {
      if (jiraDao.userExists(user.getExternalId())) {
        // return External ID if it's the user's Login ID/Username in Jira
        return Optional.of(user.getExternalId());

      } else if (user.getExternalId().split("@").length == 2) {
        // if External ID is not the Login ID but it looks like an email, try to find a user with that email
        return jiraDao.findUsernameByEmail(user.getExternalId());
      }

    } else {
      // If user has no defined External ID, use his/her email to check for a Jira user
      return jiraDao.findUsernameByEmail(user.getEmail());
    }

    return Optional.empty();
  }

  @Override
  public void shutdown() {
    jiraDao.shutdown();
  }
}
