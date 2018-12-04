/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

import org.codejargon.fluentjdbc.api.FluentJdbcException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;

import java.util.List;
import java.util.Optional;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.jira.config.JiraConnectorConfigKey;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;

/**
 * Simple, unsophisticated access to the Jira database.
 *
 * @author shane.xie@practiceinsight.io
 */
public class JiraDb {

  private final static String TIMEZONE = RuntimeConfig.getString(JiraConnectorConfigKey.TIMEZONE).orElse("UTC");

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
    // TODO
    return Optional.empty();
  }

  public List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults) {
    // TODO
    return ImmutableList.of();
  }

  public Optional<String> findUsername(final String email) {
    // TODO
    return Optional.empty();
  }

  public void updateIssueTimeSpent(final long issueId, final long duration) {
    // TODO
  }

  public void createWorklog(final Worklog worklog) {
    // TODO
  }

  private void upsertWorklogSeqId(final long seqId) {
    if (getWorklogSeqId().isPresent()) {
      updateWorklogSeqId(seqId);
    } else {
      createWorklogSeqId(seqId);
    }
  }

  private Optional<Long> getWorklogSeqId() {
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
}
