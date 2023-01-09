/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira;

import static io.wisetime.connector.jira.JiraDao.Issue;
import static io.wisetime.connector.jira.JiraDao.Worklog;

import com.github.javafaker.Faker;
import com.google.common.base.Preconditions;
import io.wisetime.generated.connect.Tag;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author shane.xie
 */
class RandomDataGenerator {

  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static final Faker FAKER = new Faker();
  private static final String TAG_PATH = "/Jira/";

  static Issue randomIssue() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_PATH);
    return randomIssue(tag.getName());
  }

  static Issue randomIssue(final String issueKey) {
    final String[] tagParts = issueKey.split("-");
    Preconditions.checkArgument(tagParts.length == 2);
    return new Issue()
        .setId(FAKER.random().nextInt(1, 999999))
        .setProjectKey(tagParts[0])
        .setIssueNumber(tagParts[1])
        .setSummary(FAKER.lorem().characters(0, 100))
        .setTimeSpent(0L)
        .setIssueType(FAKER.lorem().word());
  }

  static List<Issue> randomIssues(int count) {
    return randomEntities(RandomDataGenerator::randomIssue, count, count);
  }

  static Worklog randomWorklog() {
    return new Worklog()
        .setAuthor(FAKER.internet().emailAddress())
        .setBody(FAKER.book().title())
        .setTimeWorked(FAKER.random().nextInt(120, 3600))
        .setCreated(Instant.ofEpochMilli(Faker.instance().date().past(365, TimeUnit.DAYS).getTime()))
        .setIssueId(FAKER.random().nextInt(1, 999999));
  }

  private static <T> List<T> randomEntities(final Supplier<T> supplier, final int min, final int max) {
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
