/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.wisetime.connector.ServerRunner;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.config.RuntimeConfigKey;

/**
 * Connector application entry point.
 *
 * @author shane.xie@practiceinsight.io
 */
public class ConnectorLauncher {

  public static void main(final String... args) throws Exception {
    ServerRunner.createServerBuilder()
        .withTemplatePath("classpath:jira-template.ftl")
        .withWiseTimeConnector(Guice.createInjector(new JiraDbModule()).getInstance(JiraConnector.class))
        .build()
        .startServer();
  }

  /**
   * Configuration keys for the WiseTime Jira Connector.
   *
   * @author shane.xie@practiceinsight.io
   */
  public enum JiraConnectorConfigKey implements RuntimeConfigKey {

    JIRA_JDBC_URL("JIRA_JDBC_URL"),
    JIRA_DB_USER("JIRA_DB_USER"),
    JIRA_DB_PASSWORD("JIRA_DB_PASSWORD"),
    TAG_UPSERT_PATH("TAG_UPSERT_PATH"),
    TAG_UPSERT_BATCH_SIZE("TAG_UPSERT_BATCH_SIZE"),
    PROJECT_KEYS_FILTER("PROJECT_KEYS_FILTER");

    private final String configKey;

    JiraConnectorConfigKey(final String configKey) {
      this.configKey = configKey;
    }

    @Override
    public String getConfigKey() {
      return configKey;
    }
  }

  /**
   * Bind the Jira database connection via DI.
   */
  public static class JiraDbModule extends AbstractModule {
    private static final Logger log = LoggerFactory.getLogger(JiraDbModule.class);

    @Override
    protected void configure() {
      final HikariConfig hikariConfig = new HikariConfig();

      hikariConfig.setJdbcUrl(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_JDBC_URL)
              .orElseThrow(() -> new RuntimeException("Missing required JIRA_JDBC_URL configuration"))
      );

      hikariConfig.setUsername(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_DB_USER)
              .orElseThrow(() -> new RuntimeException("Missing required JIRA_DB_USER configuration"))
      );

      hikariConfig.setPassword(
          RuntimeConfig.getString(JiraConnectorConfigKey.JIRA_DB_PASSWORD)
              .orElseThrow(() -> new RuntimeException("Missing required JIRA_JDBC_PASSWORD configuration"))
      );
      hikariConfig.setConnectionTimeout(TimeUnit.MINUTES.toMillis(1));
      hikariConfig.setMaximumPoolSize(10);

      log.info("Connecting to Jira database at URL: {}, Username: {}", hikariConfig.getJdbcUrl(),
          hikariConfig.getUsername());

      bind(DataSource.class).toInstance(new HikariDataSource(hikariConfig));
    }
  }
}
