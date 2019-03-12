/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.FluentJdbcException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.generated.connect.UpsertTagRequest;

import static io.wisetime.connector.jira.ConnectorLauncher.JiraConnectorConfigKey;

import static java.lang.String.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * Simple, unsophisticated access to the Jira database.
 *
 * @author shane.xie@practiceinsight.io
 * @author alvin.llobrera@practiceinsight.io
 */
class JiraDao {
  private final Logger log = LoggerFactory.getLogger(JiraDao.class);
  private final DateTimeFormatter dateTimeFormatter;
  private final FluentJdbc fluentJdbc;

  @Inject
  JiraDao(DataSource dataSource) {
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
    dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  }

  void asTransaction(final Runnable runnable) {
    query().transaction().inNoResult(runnable);
  }

  boolean hasExpectedSchema() {
    log.info("Checking if Jira DB has correct schema...");

    final Map<String, Set<String>> requiredTablesAndColumnsMap = Maps.newHashMap();
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
        ImmutableSet.of("user_name", "lower_user_name", "lower_email_address")
    );
    requiredTablesAndColumnsMap.put(
        "worklog",
        ImmutableSet.of("id", "issueid", "author", "timeworked", "created", "worklogbody")
    );
    requiredTablesAndColumnsMap.put(
        "sequence_value_item",
        ImmutableSet.of("seq_id", "seq_name")
    );
    requiredTablesAndColumnsMap.put(
        "propertyentry",
        ImmutableSet.of("id", "property_key")
    );
    requiredTablesAndColumnsMap.put(
        "propertystring",
        ImmutableSet.of("id", "propertyvalue")
    );

    final Map<String, List<String>> actualTablesAndColumnsMap = query().databaseInspection()
        .selectFromMetaData(meta -> meta.getColumns(null, null, null, null))
        .listResult(rs -> ImmutablePair.of(rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME")))
        .stream()
        .filter(pair -> requiredTablesAndColumnsMap.containsKey(pair.getKey().toLowerCase()))
        // transform to lower case to ensure we are comparing the same case
        .collect(groupingBy(pair -> pair.getKey().toLowerCase(), mapping(pair -> pair.getValue().toLowerCase(), toList())));

    return requiredTablesAndColumnsMap.entrySet().stream()
        .allMatch(entry -> actualTablesAndColumnsMap.containsKey(entry.getKey()) &&
            actualTablesAndColumnsMap.get(entry.getKey()).containsAll(entry.getValue())
        );
  }

  boolean canQueryDb() {
    try {
      query().select("SELECT 1 from jiraissue").firstResult(Mappers.singleInteger());
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  Optional<Issue> findIssueByTagName(final String tagName) {
    return IssueKey
        .fromTagName(tagName)
        .flatMap(ik ->
            query().select("SELECT jiraissue.id, project.pkey, jiraissue.issuenum, jiraissue.summary, jiraissue.timespent "
                + "FROM project INNER JOIN jiraissue ON project.id = jiraissue.project "
                + "WHERE project.pkey = ? AND jiraissue.issuenum = ?")
                .params(
                    ik.getProjectKey(),
                    ik.getIssueNumber()
                )
                .firstResult(this::buildIssueFromResultSet)
        );
  }

  List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults, final String... projectKeys) {
    String query = "SELECT jiraissue.id, project.pkey, jiraissue.issuenum, jiraissue.summary, jiraissue.timespent "
        + "FROM project INNER JOIN jiraissue ON project.id = jiraissue.project "
        + "WHERE jiraissue.id > :startIdExclusive ";

    if (ArrayUtils.isNotEmpty(projectKeys)) {
      query += "AND project.pkey in (:projectKeys) ";
    }
    query += "ORDER BY ID ASC LIMIT :maxResults";

    return query().select(query)
        .namedParam("startIdExclusive", startIdExclusive)
        .namedParam("projectKeys", Lists.newArrayList(projectKeys))
        .namedParam("maxResults", maxResults)
        .listResult(this::buildIssueFromResultSet);
  }

  boolean userExists(final String username) {
    return query().select("SELECT user_name FROM cwd_user WHERE lower_user_name = :username")
        .namedParam("username", username.toLowerCase()) // Username in Jira Login is not case sensitive
        .firstResult(Mappers.singleString())
        .isPresent();
  }

  Optional<String> findUsernameByEmail(final String email) {
    return query().select("SELECT user_name FROM cwd_user WHERE lower_email_address = :email")
        .namedParam("email", email.toLowerCase())
        .firstResult(Mappers.singleString());
  }

  void updateIssueTimeSpent(final long issueId, final long duration) {
    query().update("UPDATE jiraissue SET timespent = :totalTimeSpent WHERE id = :jiraIssueId")
        .namedParam("totalTimeSpent", duration)
        .namedParam("jiraIssueId", issueId)
        .run();
  }

  void createWorklog(final Worklog worklog) {
    // Add 199 to the current seq ID (or 10100 as base point if no work log id is not yet set)
    // to make sure the new seq ID is not used by the connected Jira system
    long nextSeqId = getWorklogSeqId().orElse(10_100L) + 199L;

    query().update("INSERT INTO worklog (id, issueid, author, timeworked, created, worklogbody) "
        + "VALUES (:sequenceId, :jiraIssueId, :jiraUsername, :timeSpent, :createdDate, :comment);")
        .namedParam("sequenceId", nextSeqId)
        .namedParam("jiraIssueId", worklog.getIssueId())
        .namedParam("jiraUsername", worklog.getAuthor())
        .namedParam("timeSpent", worklog.getTimeWorked())
        .namedParam(
            "createdDate",
            ZonedDateTime.of(worklog.getCreated(), ZoneOffset.UTC)
                .withZoneSameInstant(getJiraDefaultTimeZone())
                .format(dateTimeFormatter)
        )
        .namedParam("comment", worklog.getBody())
        .run();

    // Update the "sequence_value_item" table for the new work log seq id used
    upsertWorklogSeqId(nextSeqId);
  }

  ZoneId getJiraDefaultTimeZone() {
    final Optional<Long> propertyId = query()
        .select("SELECT id FROM propertyentry WHERE property_key = 'jira.default.timezone'")
        .firstResult(Mappers.singleLong());

    if (propertyId.isPresent()) {
      return ZoneId.of(
          query().select("SELECT propertyvalue from propertystring WHERE id = ?")
              .params(propertyId.get())
              .singleResult(Mappers.singleString())
      );
    }

    return ZoneId.of(RuntimeConfig.getString(JiraConnectorConfigKey.TIMEZONE).orElse("UTC"));
  }

  private void upsertWorklogSeqId(final long seqId) {
    if (getWorklogSeqId().isPresent()) {
      updateWorklogSeqId(seqId);
    } else {
      createWorklogSeqId(seqId);
    }
  }

  Optional<Long> getWorklogSeqId() {
    try {
      final long seqId = query().select("SELECT seq_id FROM SEQUENCE_VALUE_ITEM WHERE seq_name='Worklog'")
          .singleResult(Mappers.singleLong());
      return Optional.of(seqId);
    } catch (FluentJdbcException e) {
      return Optional.empty();
    }
  }

  private void createWorklogSeqId(final long seqId) {
    query().update("INSERT INTO SEQUENCE_VALUE_ITEM (seq_name, seq_id) VALUES ('Worklog', ?)")
        .params(seqId)
        .run();
  }

  private void updateWorklogSeqId(final long newSeqId) {
    query().update("UPDATE SEQUENCE_VALUE_ITEM SET seq_id=? WHERE seq_name='Worklog'")
        .params(newSeqId)
        .run();
  }

  private Issue buildIssueFromResultSet(final ResultSet resultSet) throws SQLException {
    return Issue.builder()
        .id(resultSet.getLong("jiraissue.id"))
        .projectKey(resultSet.getString("project.pkey"))
        .issueNumber(resultSet.getString("jiraissue.issuenum"))
        .summary(resultSet.getString("jiraissue.summary"))
        .timeSpent(resultSet.getLong("jiraissue.timespent"))
        .build();
  }

  private Query query() {
    return fluentJdbc.query();
  }

  /**
   * Models a Jira issue.
   */
  @Value.Immutable
  public interface Issue {

    long getId();

    String getProjectKey();

    String getIssueNumber();

    String getSummary();

    long getTimeSpent();

    /**
     * A Jira issue key is made up of {projectKey}-{issueNumber} E.g. WT-1234
     */
    default String getKey() {
      return format("%s-%s", getProjectKey(), getIssueNumber());
    }

    default UpsertTagRequest toUpsertTagRequest(final String path) {
      return new UpsertTagRequest()
          .name(getKey())
          .description(getSummary())
          .path(path)
          .additionalKeywords(ImmutableList.of(getKey()));
    }

    static ImmutableIssue.Builder builder() {
      return ImmutableIssue.builder();
    }
  }

  /**
   * Jira issue reference
   */
  @Value.Immutable
  public interface IssueKey {

    String getProjectKey();

    Integer getIssueNumber();

    static Optional<IssueKey> fromTagName(final String tagName) {
      try {
        final String[] parts = tagName.split("-");
        if (parts.length == 2) {
          return Optional.of(
              ImmutableIssueKey
                  .builder()
                  .projectKey(parts[0])
                  .issueNumber(Integer.parseInt(parts[1]))
                  .build()
          );
        }
        return Optional.empty();
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
    }
  }

  /**
   * Models a Jira Worklog.
   */
  @Value.Immutable
  public interface Worklog {

    long getIssueId();

    String getAuthor();

    long getTimeWorked();

    LocalDateTime getCreated();

    String getBody();

    static ImmutableWorklog.Builder builder() {
      return ImmutableWorklog.builder();
    }
  }
}
