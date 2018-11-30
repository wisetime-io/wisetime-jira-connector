/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.query.Query;

/**
 * @author shane.xie@practiceinsight.io
 */
class QueryProvider implements Provider<Query> {

  @Inject
  private Provider<FluentJdbc> jdbcProvider;

  @Override
  public Query get() {
    return jdbcProvider.get().query();
  }
}
