/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.testutils;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.config.RuntimeConfigKey;

/**
 * @author shane.xie@practiceinsight.io
 */
public class RuntimeConfigHelper {

  public static void setConfig(final RuntimeConfigKey key, final int value) {
    setConfig(key, String.valueOf(value));
  }

  public static void setConfig(final RuntimeConfigKey key, final String value) {
    System.setProperty(key.getConfigKey(), value);
    RuntimeConfig.rebuild();
  }
}
