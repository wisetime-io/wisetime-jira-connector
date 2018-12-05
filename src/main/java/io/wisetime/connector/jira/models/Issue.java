/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.models;

import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import io.wisetime.generated.connect.UpsertTagRequest;

import static java.lang.String.format;

/**
 * Models a Jira issue.
 *
 * @author shane.xie@practiceinsight.io
 */
@Value.Immutable
public interface Issue {

  long getId();

  String getProjectKey();

  String getIssueNumber();

  String getSummary();

  long getTimeSpent();

  default String getKey() {
    return format("%s-%s", getProjectKey(), getIssueNumber());
  }

  default UpsertTagRequest toUpsertTagRequest(final String path) {
    return new UpsertTagRequest()
        .name(getKey())
        .description(getSummary())
        .path(path)
        .additionalKeywords(ImmutableList.of(getKey()));
  }
}
