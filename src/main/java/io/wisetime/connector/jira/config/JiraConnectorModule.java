/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.query.Query;

import java.util.Optional;

import javax.sql.DataSource;

import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;

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

    bind(new TypeLiteral<Optional<String>>() {})
        .annotatedWith(CallerKey.class)
        .toProvider(() ->
            RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY));

    bind(String.class)
        .annotatedWith(TagUpsertPath.class)
        .toProvider(() ->
            RuntimeConfig
                .getString(JiraConnectorConfigKey.TAG_UPSERT_PATH)
                .orElse("/Jira"));

    bind(Integer.class)
        .annotatedWith(TagUpsertBatchSize.class)
        .toProvider(() ->
            RuntimeConfig
                .getInt(JiraConnectorConfigKey.TAG_UPSERT_BATCH_SIZE)
                // A large batch mitigates query round trip latency
                .orElse(500));
  }
}
