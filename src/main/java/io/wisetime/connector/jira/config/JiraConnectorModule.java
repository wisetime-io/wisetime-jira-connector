/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.query.Query;

import javax.sql.DataSource;

/**
 * Wire up application dependencies.
 *
 * @author shane.xie@practiceinisght.io
 */
public class JiraConnectorModule extends AbstractModule {

  @Override
  protected void configure() {
    bind(DataSource.class)
        .toProvider(DataSourceProvider.class)
        .in(Singleton.class);

    bind(FluentJdbc.class)
        .toProvider(FluentJdbcProvider.class)
        .in(Singleton.class);

    bind(Query.class)
        .toProvider(QueryProvider.class)
        .in(Singleton.class);
  }
}
