/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.inject.Guice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.wisetime.connector.jira.database.JiraDb;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * @author shane.xie#practiceinsight.io
 */
class JiraConnectorPerformTagUpdateTest {

  private static JiraConnector connector;
  private static JiraDb jiraDb;

  @BeforeAll
  static void setUp() {
    jiraDb = mock(JiraDb.class);

    connector = Guice.createInjector(binder -> {
      binder.bind(JiraDb.class).toProvider(() -> jiraDb);
    }).getInstance(JiraConnector.class);
  }

  @BeforeEach
  void setUpTest() {
    reset(jiraDb);
  }

  @Test
  void performTagUpdate() {
  }
}