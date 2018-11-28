/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.generated.connect.TimeGroup;
import spark.Request;

/**
 * @author shane.xie@practiceinsight.io
 */
public class JiraConnector implements WiseTimeConnector {

  @Override
  public void init(ConnectorModule connectorModule) {
  }

  @Override
  public void performTagUpdate() {
  }

  @Override
  public PostResult postTime(Request request, TimeGroup userPostedTime) {
    return null;
  }

  @Override
  public boolean isConnectorHealthy() {
    return false;
  }
}
