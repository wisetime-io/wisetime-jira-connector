/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import org.immutables.value.Value;

import java.time.LocalDateTime;

/**
 * Models a Jira Worklog.
 *
 * @author shane.xie@practiceinsight.io
 */
@Value.Immutable
public interface Worklog {

  long getIssueId();

  String getAuthor();

  long getTimeWorked();

  LocalDateTime getCreated();

  String getBody();

  static ImmutableWorklog.Builder builder() {
    return ImmutableWorklog.builder();
  }

}
