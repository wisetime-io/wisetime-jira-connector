/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.jira.testutils;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import javax.sql.DataSource;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
public class FlywayJiraTestDbModule extends AbstractModule {

  @Override
  protected void configure() {
    bind(Flyway.class).toProvider(FlywayJiraProvider.class);
  }

  private static class FlywayJiraProvider implements Provider<Flyway> {

    @Inject
    private Provider<DataSource> dataSourceProvider;

    @Override
    public Flyway get() {
      Flyway flyway = new Flyway();
      flyway.setDataSource(dataSourceProvider.get());
      flyway.setBaselineVersion(MigrationVersion.fromVersion("0"));
      flyway.setBaselineOnMigrate(true);
      flyway.setLocations("jira_db_schema/");
      return flyway;
    }
  }
}
