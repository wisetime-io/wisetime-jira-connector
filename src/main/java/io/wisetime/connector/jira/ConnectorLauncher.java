/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import io.wisetime.connector.ServerRunner;

/**
 * @author shane.xie@practiceinsight.io
 */
public class ConnectorLauncher {

  // Application entry point
  public static void main(final String... args) throws Exception {

    ServerRunner.createServerBuilder()
        .withWiseTimeConnector(new JiraConnector())
        .build()
        .startServer();
  }
}
