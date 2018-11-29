/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

import org.codejargon.fluentjdbc.api.query.Query;

import java.util.List;

import io.wisetime.connector.jira.models.Issue;

/**
 * @author shane.xie@practiceinsight.io
 */
public class JiraDb {

  @Inject
  private Query query;

  public boolean canUseDatabase() {
    // TODO
    return true;
  }

  public List<Issue> findIssuesOrderedById(final long startIdExclusive, final int maxResults) {
    // TODO
    return ImmutableList.of();
  }
}
