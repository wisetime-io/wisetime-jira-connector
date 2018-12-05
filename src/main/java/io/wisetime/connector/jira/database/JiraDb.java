/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.codejargon.fluentjdbc.api.FluentJdbcException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.jira.models.ImmutableIssue;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;

/**
 * Simple, unsophisticated access to the Jira database.
 *
 * @author shane.xie@practiceinsight.io
 */
public class JiraDb {


  private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Inject
  private ZoneId zoneId;

  @Inject
  private Query query;

  public void asTransaction(final Runnable runnable) {
    query.transaction().inNoResult(runnable);
  }

  public boolean canUseDatabase() {
    // TODO
    return true;
  }

  public Optional<Issue> findIssueByTagName(final String tagName) {
    Optional<Pair<String, Integer>> projectIssuePair = getJiraProjectIssuePair(tagName);

    if (!projectIssuePair.isPresent()) {
      return Optional.empty();
    }

    return query.select("SELECT jiraissue.id, project.pkey, jiraissue.issuenum, jiraissue.summary, jiraissue.timespent "
            + "FROM project INNER JOIN jiraissue ON project.id = jiraissue.project "
            + "WHERE project.pkey = ? AND jiraissue.issuenum = ?")
        .params(
            projectIssuePair.get().getLeft(),
            projectIssuePair.get().getRight()
        )
        .firstResult(resultSet -> ImmutableIssue.builder()
            .id(resultSet.getLong(1))
            .projectKey(resultSet.getString(2))
            .issueNumber(resultSet.getString(3))
            .summary(resultSet.getString(4))
            .timeSpent(resultSet.getLong(5))
            .build()
        );
  }

  public List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults) {
    return query.select("SELECT jiraissue.id, project.pkey, jiraissue.issuenum, jiraissue.summary, jiraissue.timespent "
        + "FROM project INNER JOIN jiraissue ON project.id = jiraissue.project "
        + "WHERE jiraissue.id > ? ORDER BY ID ASC LIMIT ?;")
        .params(
            startIdExclusive,
            maxResults
        )
        .listResult(resultSet -> ImmutableIssue.builder()
            .id(resultSet.getLong(1))
            .projectKey(resultSet.getString(2))
            .issueNumber(resultSet.getString(3))
            .summary(resultSet.getString(4))
            .timeSpent(resultSet.getLong(5))
            .build()
        );
  }

  public Optional<String> findUsername(final String email) {
    return query.select("SELECT user_name FROM cwd_user WHERE lower_email_address = :email")
        .namedParam("email", email.toLowerCase())
        .firstResult(resultSet -> resultSet.getString(1));
  }

  public void updateIssueTimeSpent(final long issueId, final long duration) {
    query.update("UPDATE jiraissue SET timespent = :totalTimeSpent WHERE id = :jiraIssueId")
        .namedParam("totalTimeSpent", duration)
        .namedParam("jiraIssueId", issueId)
        .run();
  }

  public void createWorklog(final Worklog worklog) {
    // Add 199 to the current seq ID (or 10100 as base point if no work log id is not yet set)
    // to make sure the new seq ID is not used by the connected Jira system
    Long nextSeqId = getWorklogSeqId().orElse(10100L) + 199L;

    query.update("INSERT INTO worklog (id, issueid, author, timeworked, created, worklogbody) "
        + "VALUES (:sequenceId, :jiraIssueId, :jiraUsername, :timeSpent, :createdDate, :comment);")
        .namedParam("sequenceId", nextSeqId)
        .namedParam("jiraIssueId", worklog.getIssueId())
        .namedParam("jiraUsername", worklog.getAuthor())
        .namedParam("timeSpent", worklog.getTimeWorked())
        .namedParam("createdDate", ZonedDateTime.of(worklog.getCreated(), zoneId).format(dateTimeFormatter))
        .namedParam("comment", worklog.getBody())
        .run();

    // Update the "sequence_value_item" table for the new work log seq id used
    upsertWorklogSeqId(nextSeqId);
  }

  private void upsertWorklogSeqId(final long seqId) {
    if (getWorklogSeqId().isPresent()) {
      updateWorklogSeqId(seqId);
    } else {
      createWorklogSeqId(seqId);
    }
  }

  @VisibleForTesting
  Optional<Long> getWorklogSeqId() {
    try {
      final long seqId = query.select("SELECT seq_id FROM sequence_value_item WHERE seq_name='Worklog'")
          .singleResult(Mappers.singleLong());
      return Optional.of(seqId);
    } catch (FluentJdbcException e) {
      return Optional.empty();
    }
  }

  private void createWorklogSeqId(final long seqId) {
    query.update("INSERT INTO sequence_value_item (seq_name, seq_id) VALUES ('Worklog', ?)")
        .params(seqId)
        .run();
  }

  private void updateWorklogSeqId(final long newSeqId) {
    query.update("UPDATE sequence_value_item SET seq_id=? WHERE seq_name='Worklog'")
        .params(newSeqId)
        .run();
  }

  private Optional<Pair<String, Integer>> getJiraProjectIssuePair(String tagName) {
    try {
      final String[] parts = tagName.split("-");
      if (parts.length == 2) {
        return Optional.of(Pair.of(parts[0], Integer.parseInt(parts[1])));
      }
      return Optional.empty();
    } catch (Exception ex) {
      return Optional.empty();
    }
  }
}
