/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.models;

import com.google.common.collect.ImmutableList;

import com.github.javafaker.Faker;

import org.junit.jupiter.api.Test;

import io.wisetime.generated.connect.UpsertTagRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author shane.xie@practiceinsight.io
 */
class IssueTest {

  private static Faker faker = new Faker();

  @Test
  void toUpsertTagRequest() {
    final Issue issue = ImmutableIssue
        .builder()
        .id(faker.number().randomNumber())
        .projectKey("WT")
        .issueNumber(faker.numerify("####"))
        .summary(faker.gameOfThrones().quote())
        .build();

    final UpsertTagRequest request = issue.toUpsertTagRequest("/Jira");
    final String tagName = issue.getProjectKey() + "-" + issue.getIssueNumber();

    assertThat(request)
        .isEqualTo(new UpsertTagRequest()
            .name(tagName)
            .description(issue.getSummary())
            .additionalKeywords(ImmutableList.of(tagName))
            .path("/Jira")
        );
  }
}