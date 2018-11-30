/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.Provider;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * @author shane.xie@practiceinsight.io
 */
public class DataSourceProvider implements Provider<DataSource> {

  private static final Logger log = LoggerFactory.getLogger(DataSourceProvider.class);

  @Override
  public DataSource get() {
    final HikariConfig hikariConfig = new HikariConfig();

    // TODO: Get values from config
    hikariConfig.setJdbcUrl("TODO from config");
    hikariConfig.setUsername("TODO from config");
    hikariConfig.setPassword("TODO from config");
    hikariConfig.setConnectionTimeout(TimeUnit.MINUTES.toMillis(1));
    hikariConfig.setMaximumPoolSize(10);

    log.info("Connecting to Jira database at URL: {}, Username: {}", hikariConfig.getJdbcUrl(), hikariConfig.getUsername());
    return new HikariDataSource(hikariConfig);
  }
}