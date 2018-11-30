/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author shane.xie@practiceinsight.io
 */
class JiraDbTest {

  @Test
  void canUseDatabase() {
  }

  @Test
  void findIssuesOrderedById() {
  }

// https://github.com/zsoltherpai/fluent-jdbc/wiki/Query-listener
//
//  AfterQueryListener listener = execution -> {
//    if(execution.success()) {
//        log.debug(
//            String.format(
//                "Query took %s ms to execute: %s",
//                execution.executionTimeMs(),
//                execution.sql()
//            )
//        )
//    }
//  };
//
//  FluentJdbc fluentJdbc = new FluentJdbcBuilder()
//      // other configuration
//      .afterQueryListener(listener)
//      .build();
}