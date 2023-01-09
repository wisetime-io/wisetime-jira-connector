/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import static java.lang.String.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.FluentJdbcException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple, unsophisticated access to the Jira database.
 *
 * @author shane.xie
 * @author alvin.llobrera
 */
class JiraDao {
  private final Logger log = LoggerFactory.getLogger(JiraDao.class);
  private final FluentJdbc fluentJdbc;
  private final HikariDataSource dataSource;

  @Inject
  JiraDao(HikariDataSource dataSource) {
    this.dataSource = dataSource;
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  void asTransaction(final Runnable runnable) {
    query().transaction().inNoResult(runnable);
  }

  boolean hasExpectedSchema() {
    log.info("Checking if Jira DB has correct schema...");

    final Map<String, Set<String>> requiredTablesAndColumnsMap = Maps.newHashMap();
    requiredTablesAndColumnsMap.put(
        "jiraissue",
        ImmutableSet.of("id", "issuenum", "summary", "timespent", "project", "issuetype")
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
    requiredTablesAndColumnsMap.put(
        "issuetype",
        ImmutableSet.of("id", "pname")
    );

    final Map<String, List<String>> actualTablesAndColumnsMap = query().databaseInspection()
        .selectFromMetaData(meta -> meta.getColumns(null, null, null, null))
        .listResult(rs -> ImmutablePair.of(rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME")))
        .stream()
        .filter(pair -> requiredTablesAndColumnsMap.containsKey(pair.getKey().toLowerCase()))
        // transform to lower case to ensure we are comparing the same case
        .collect(groupingBy(pair -> pair.getKey().toLowerCase(), mapping(pair -> pair.getValue().toLowerCase(), toList())));

    return requiredTablesAndColumnsMap.entrySet().stream()
        .allMatch(entry -> actualTablesAndColumnsMap.containsKey(entry.getKey())
            && actualTablesAndColumnsMap.get(entry.getKey()).containsAll(entry.getValue())
        );
  }

  /**
   * Low cost query to check if we can query the db.
   *
   * @return true, if the db is reachable
   */
  boolean pingDb() {
    try {
      query().select("SELECT 1 from jiraissue LIMIT 1").firstResult(Mappers.singleInteger());
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  long issueCount(final String... projectKeys) {
    String query = "SELECT COUNT(*) "
        + "FROM jiraissue INNER JOIN project ON project.id = jiraissue.project ";

    if (ArrayUtils.isNotEmpty(projectKeys)) {
      query += "WHERE project.pkey in (:projectKeys) ";
    }
    return query().select(query)
        .namedParam("projectKeys", Lists.newArrayList(projectKeys))
        .firstResult(Mappers.singleLong())
        .orElse(0L);
  }

  Optional<Issue> findIssueByTagName(final String tagName) {
    return IssueKey
        .fromTagName(tagName)
        .flatMap(ik ->
            query().select("SELECT jiraissue.id, project.pkey, jiraissue.issuenum, "
                + "jiraissue.summary, jiraissue.timespent, issuetype.pname "
                + "FROM project "
                + "INNER JOIN jiraissue ON project.id = jiraissue.project "
                + "LEFT JOIN issuetype ON issuetype.id = jiraissue.issuetype "
                + "WHERE project.pkey = ? AND jiraissue.issuenum = ?")
                .params(
                    ik.getProjectKey(),
                    ik.getIssueNumber()
                )
                .firstResult(this::buildIssueFromResultSet)
        );
  }

  List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults, final String... projectKeys) {
    String query = "SELECT jiraissue.id, project.pkey, jiraissue.issuenum,"
        + "jiraissue.summary, jiraissue.timespent, issuetype.pname "
        + "FROM project "
        + "INNER JOIN jiraissue ON project.id = jiraissue.project "
        + "LEFT JOIN issuetype ON issuetype.id = jiraissue.issuetype "
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
        .namedParam("createdDate", worklog.getCreated())
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
    // Important to keep the order of the of the columns in the SELECT statement
    // unfortunately getting them by name is handled differently in MySQL and pg jdbc drivers
    return new Issue()
        .setId(resultSet.getLong(1))
        .setProjectKey(resultSet.getString(2))
        .setIssueNumber(resultSet.getString(3))
        .setSummary(resultSet.getString(4))
        .setTimeSpent(resultSet.getLong(5))
        .setIssueType(StringUtils.trimToEmpty(resultSet.getString(6)));
  }

  private Query query() {
    return fluentJdbc.query();
  }

  void shutdown() {
    dataSource.close();
  }

  /**
   * Models a Jira issue.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder(toBuilder = true)
  @Accessors(chain = true)
  public static class Issue {

    private long id;
    private String projectKey;
    private String issueNumber;
    private String summary;
    private long timeSpent;
    private String issueType;

    /**
     * A Jira issue key is made up of {projectKey}-{issueNumber} E.g. WT-1234
     */
    public String getKey() {
      return format("%s-%s", getProjectKey(), getIssueNumber());
    }

    public UpsertTagRequest toUpsertTagRequest(final String path) {
      final String key = getKey();
      return new UpsertTagRequest()
          .name(key)
          .description(summary)
          .path(path)
          .additionalKeywords(ImmutableList.of(key))
          .metadata(generateMetadata())
          .externalId(id + "");
    }

    public Map<String, String> generateMetadata() {
      final Map<String, String> metadata = new HashMap<>();
      metadata.put("Project", StringUtils.trimToNull(getProjectKey()));
      metadata.put("Type", StringUtils.trimToNull(getIssueType()));
      return metadata;
    }
  }

  /**
   * Jira issue reference
   */
  @Data
  @Accessors(chain = true)
  public static class IssueKey {

    private String projectKey;
    private int issueNumber;

    static Optional<IssueKey> fromTagName(final String tagName) {
      try {
        final String[] parts = tagName.split("-");
        if (parts.length == 2) {
          return Optional.of(
              new IssueKey()
                  .setProjectKey(parts[0])
                  .setIssueNumber(Integer.parseInt(parts[1]))
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
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder(toBuilder = true)
  @Accessors(chain = true)
  public static class Worklog {

    private long issueId;
    private String author;
    private long timeWorked;
    private Instant created;
    private String body;
  }
}
