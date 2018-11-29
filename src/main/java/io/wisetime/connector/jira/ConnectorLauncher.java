/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.inject.Guice;
import com.google.inject.Injector;

import io.wisetime.connector.ServerRunner;

/**
 * @author shane.xie@practiceinsight.io
 */
public class ConnectorLauncher {

  public static void main(final String... args) throws Exception {
    final Injector injector = Guice.createInjector(new JiraConnectorModule());

    ServerRunner.createServerBuilder()
        .withWiseTimeConnector(injector.getInstance(JiraConnector.class))
        .build()
        .startServer();
  }
}
