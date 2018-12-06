/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.codejargon.fluentjdbc.api.FluentJdbcException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.wisetime.connector.jira.models.ImmutableIssue;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * Simple, unsophisticated access to the Jira database.
 *
 * @author shane.xie@practiceinsight.io
 * @author alvin.llobrera@practiceinsight.io
 */
public class JiraDb {

  private final Logger log = LoggerFactory.getLogger(JiraDb.class);

  private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Inject
  private ZoneId zoneId;

  @Inject
  private Query query;

  public void asTransaction(final Runnable runnable) {
    query.transaction().inNoResult(runnable);
  }

  public boolean hasExpectedSchema() {
    Map<String, Set<String>> requiredTablesAndColumnsMap = Maps.newHashMap();
    requiredTablesAndColumnsMap.put(
        "jiraissue",
        ImmutableSet.of("id", "issuenum", "summary", "timespent", "project")
    );
    requiredTablesAndColumnsMap.put(
        "project",
        ImmutableSet.of("id", "pkey")
    );
    requiredTablesAndColumnsMap.put(
        "cwd_user",
        ImmutableSet.of("user_name", "lower_email_address")
    );
    requiredTablesAndColumnsMap.put(
        "worklog",
        ImmutableSet.of("id", "issueid", "author", "timeworked", "created", "worklogbody")
    );
    requiredTablesAndColumnsMap.put(
        "sequence_value_item",
        ImmutableSet.of("seq_id", "seq_name")
    );

    Map<String, List<String>> actualTablesAndColumnsMap = query.databaseInspection()
        .selectFromMetaData(meta -> meta.getColumns(null, null, null, null))
        .listResult(rs -> ImmutablePair.of(rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME")))
        .stream()
        .filter(pair -> requiredTablesAndColumnsMap.containsKey(pair.getKey().toLowerCase()))
        .collect(groupingBy(ImmutablePair::getKey, mapping(ImmutablePair::getValue, toList())));

    return requiredTablesAndColumnsMap.entrySet().stream()
        // Values from MetaDataResultSet are in uppercase
        .allMatch(entry -> actualTablesAndColumnsMap.containsKey(entry.getKey().toUpperCase()) &&
                actualTablesAndColumnsMap.get(entry.getKey().toUpperCase()).containsAll(
                    entry.getValue().stream().map(String::toUpperCase).collect(Collectors.toSet()))
        );
  }

  public boolean canQueryDatabase() {
    query.select("SELECT 1 FROM jiraissue").firstResult(ResultSet::getRow);

    // If above query did not fail, it means we can connect to Jira DB
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
        .firstResult(this::buildIssueFromResultSet);
  }

  public List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults) {
    return query.select("SELECT jiraissue.id, project.pkey, jiraissue.issuenum, jiraissue.summary, jiraissue.timespent "
        + "FROM project INNER JOIN jiraissue ON project.id = jiraissue.project "
        + "WHERE jiraissue.id > ? ORDER BY ID ASC LIMIT ?;")
        .params(
            startIdExclusive,
            maxResults
        )
        .listResult(this::buildIssueFromResultSet
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

  private ImmutableIssue buildIssueFromResultSet(ResultSet resultSet) throws SQLException {
    return ImmutableIssue.builder()
        .id(resultSet.getLong("jiraissue.id"))
        .projectKey(resultSet.getString("project.pkey"))
        .issueNumber(resultSet.getString("jiraissue.issuenum"))
        .summary(resultSet.getString("jiraissue.summary"))
        .timeSpent(resultSet.getLong("jiraissue.timespent"))
        .build();
  }

  private Optional<Pair<String, Integer>> getJiraProjectIssuePair(String tagName) {
    try {
      final String[] parts = tagName.split("-");
      if (parts.length == 2) {
        return Optional.of(Pair.of(parts[0], Integer.parseInt(parts[1])));
      }
      return Optional.empty();
    } catch (NumberFormatException ex) {
      log.warn("Unable to extract Jira issue number from {}.", tagName);
      return Optional.empty();
    }
  }
}
