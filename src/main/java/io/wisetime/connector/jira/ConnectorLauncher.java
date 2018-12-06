/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.inject.Guice;
import com.google.inject.Injector;

import io.wisetime.connector.ServerRunner;
import io.wisetime.connector.jira.database.JiraDbModule;

/**
 * Connector application entry point.
 *
 * @author shane.xie@practiceinsight.io
 */
public class ConnectorLauncher {

  public static void main(final String... args) throws Exception {
    final Injector injector = Guice.createInjector(new JiraDbModule());

    ServerRunner.createServerBuilder()
        .withWiseTimeConnector(injector.getInstance(JiraConnector.class))
        .build()
        .startServer();
  }
}
