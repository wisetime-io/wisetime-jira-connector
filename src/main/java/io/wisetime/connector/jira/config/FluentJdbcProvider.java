/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;

import javax.sql.DataSource;

/**
 * @author shane.xie@practiceinsight.io
 */
public class FluentJdbcProvider implements Provider<FluentJdbc> {

  @Inject
  private Provider<DataSource> dataSource;

  @Override
  public FluentJdbc get() {
    return new FluentJdbcBuilder().connectionProvider(dataSource.get()).build();
  }
}

