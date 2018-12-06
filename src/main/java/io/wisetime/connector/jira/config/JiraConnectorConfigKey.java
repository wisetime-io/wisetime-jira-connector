/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import io.wisetime.connector.config.RuntimeConfigKey;

/**
 * Configurations specific to the WiseTime Jira Connector
 *
 * @author shane.xie@practiceinsight.io
 */
public enum JiraConnectorConfigKey implements RuntimeConfigKey {

  JIRA_JDBC_URL("JIRA_JDBC_URL"),
  JIRA_JDBC_USER("JIRA_JDBC_USER"),
  JIRA_JDBC_PASSWORD("JIRA_JDBC_PASSWORD"),
  TAG_UPSERT_PATH("TAG_UPSERT_PATH"),
  TAG_UPSERT_BATCH_SIZE("TAG_UPSERT_BATCH_SIZE");

  private final String configKey;

  JiraConnectorConfigKey(final String configKey) {
    this.configKey = configKey;
  }

  @Override
  public String getConfigKey() {
    return configKey;
  }
}
