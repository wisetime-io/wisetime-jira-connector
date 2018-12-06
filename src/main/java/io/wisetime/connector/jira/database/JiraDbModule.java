/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.jira.JiraConnectorConfigKey;

/**
 * Wire up application dependencies.
 *
 * @author shane.xie@practiceinisght.io
 */
public class JiraDbModule extends AbstractModule {

  @Override
  protected void configure() {

    bind(DataSource.class)
        .toProvider(JiraDataSourceProvider.class)
        .in(Singleton.class);

    bind(FluentJdbc.class)
        .toProvider(FluentJdbcProvider.class)
        .in(Singleton.class);

  }

  private static class JiraDataSourceProvider implements Provider<DataSource> {
    private static final Logger log = LoggerFactory.getLogger(JiraDataSourceProvider.class);

    @Override
    public DataSource get() {
      final HikariConfig hikariConfig = new HikariConfig();

      hikariConfig.setJdbcUrl(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_URL).orElseThrow(() ->
              new RuntimeException("Missing required JIRA_JDBC_URL configuration")
          )
      );
      hikariConfig.setUsername(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_USER).orElseThrow(() ->
              new RuntimeException("Missing required JIRA_JDBC_USER configuration")
          )
      );
      hikariConfig.setPassword(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_PASSWORD).orElseThrow(() ->
              new RuntimeException("Missing required JIRA_JDBC_PASSWORD configuration")
          )
      );
      hikariConfig.setConnectionTimeout(TimeUnit.MINUTES.toMillis(1));
      hikariConfig.setMaximumPoolSize(10);

      log.info("Connecting to Jira database at URL: {}, Username: {}", hikariConfig.getJdbcUrl(),
          hikariConfig.getUsername());
      return new HikariDataSource(hikariConfig);
    }
  }

  private static class FluentJdbcProvider implements Provider<FluentJdbc> {

    @Inject
    private Provider<DataSource> jdbcProvider;

    @Override
    public FluentJdbc get() {
      return new FluentJdbcBuilder().connectionProvider(jdbcProvider.get()).build();
    }
  }

}
