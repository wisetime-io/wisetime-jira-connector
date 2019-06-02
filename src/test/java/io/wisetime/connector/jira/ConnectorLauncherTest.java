/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author vadym
 */
class ConnectorLauncherTest {

  @Test
  void logConnecting_postgres() {
    String samplePostgresUrl = "jdbc:postgresql://localhost:5432/test?user=fred&password=secret&ssl=true";
    assertThat(new ConnectorLauncher.JiraDbModule().buildSafeJdbcUrl(samplePostgresUrl))
        .as("password and user have to be excluded from output")
        .isEqualTo("localhost:5432/test");
  }

  @Test
  void logConnecting_mysql() {
    String samplePostgresUrl = "jdbc:mysql://user:password@localhost:3306/test";
    assertThat(new ConnectorLauncher.JiraDbModule().buildSafeJdbcUrl(samplePostgresUrl))
        .as("password and user have to be excluded from output")
        .isEqualTo("localhost:3306/test");
  }

  @Test
  void logConnecting_noPort() {
    String samplePostgresUrl = "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true";
    assertThat(new ConnectorLauncher.JiraDbModule().buildSafeJdbcUrl(samplePostgresUrl))
        .as("check with default port")
        .isEqualTo("localhost:default/test");
  }
}