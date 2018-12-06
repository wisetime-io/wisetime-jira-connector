/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.database;

import com.google.common.collect.ImmutableList;

import org.junit.jupiter.api.Test;

import io.wisetime.connector.jira.FakeEntities;
import io.wisetime.generated.connect.UpsertTagRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author shane.xie@practiceinsight.io
 */
class IssueTest {

  private FakeEntities fakeEntities = new FakeEntities();

  @Test
  void toUpsertTagRequest() {
    final Issue issue = fakeEntities.randomIssue();
    final UpsertTagRequest request = issue.toUpsertTagRequest("/Jira/");
    final String tagName = issue.getProjectKey() + "-" + issue.getIssueNumber();

    assertThat(request)
        .isEqualTo(new UpsertTagRequest()
            .name(tagName)
            .description(issue.getSummary())
            .additionalKeywords(ImmutableList.of(tagName))
            .path("/Jira/"));
  }
}