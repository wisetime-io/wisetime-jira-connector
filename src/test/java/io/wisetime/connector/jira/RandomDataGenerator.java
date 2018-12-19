/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import com.google.common.base.Preconditions;

import com.github.javafaker.Faker;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.wisetime.connector.testutils.FakeEntities;
import io.wisetime.generated.connect.Tag;

import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;

/**
 * @author shane.xie@practiceinsight.io
 */
class RandomDataGenerator {

  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static final Faker FAKER = new Faker();
  private static final String TAG_PATH = "/Jira/";

  Issue randomIssue() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_PATH);
    return randomIssue(tag.getName());
  }

  Issue randomIssue(final String issueKey) {
    final String[] tagParts = issueKey.split("-");
    Preconditions.checkArgument(tagParts.length == 2);
    return Issue
        .builder()
        .id(FAKER.random().nextInt(1, 999999))
        .projectKey(tagParts[0])
        .issueNumber(tagParts[1])
        .summary(FAKER.lorem().characters(0, 100))
        .timeSpent(0L)
        .build();
  }

  List<Issue> randomIssues(int count) {
    return randomEntities(this::randomIssue, count, count);
  }

  Worklog randomWorklog(ZoneId zoneId) {
    return Worklog.builder()
        .author(FAKER.internet().emailAddress())
        .body(FAKER.book().title())
        .timeWorked(FAKER.random().nextInt(120, 3600))
        .created(ZonedDateTime.now(zoneId).toLocalDateTime())
        .issueId(FAKER.random().nextInt(1, 999999))
        .build();
  }

  private <T> List<T> randomEntities(final Supplier<T> supplier, final int min, final int max) {
    return IntStream
        .range(0, FAKER.random().nextInt(min, max))
        .mapToObj(i -> supplier.get())
        .collect(Collectors.toList());
  }

  private static <T extends Enum<?>> T randomEnum(final Class<T> clazz) {
    final int index = FAKER.random().nextInt(clazz.getEnumConstants().length);
    return clazz.getEnumConstants()[index];
  }
}
