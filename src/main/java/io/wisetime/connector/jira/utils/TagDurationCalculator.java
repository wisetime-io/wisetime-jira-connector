/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.utils;

import io.wisetime.generated.connect.TimeGroup;

/**
 * @author shane.xie@practiceinsight.io
 */
public class TagDurationCalculator {

  public static double tagDurationSecs(final TimeGroup timeGroup) {
    if (timeGroup.getTags().size() == 0) {
      return 0;
    }
    switch (timeGroup.getDurationSplitStrategy()) {
      case WHOLE_DURATION_TO_EACH_TAG:
        return timeGroup.getTotalDurationSecs();
      case DIVIDE_BETWEEN_TAGS:
      default:
        return timeGroup.getTotalDurationSecs() / timeGroup.getTags().size();
    }
  }
}
