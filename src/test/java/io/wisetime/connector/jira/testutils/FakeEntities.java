/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.testutils;

import com.google.common.base.Preconditions;

import com.github.javafaker.Faker;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.wisetime.connector.jira.models.ImmutableIssue;
import io.wisetime.connector.jira.models.ImmutableWorklog;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.connector.jira.models.Worklog;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;

/**
 * @author shane.xie@practiceinsight.io
 */
public class FakeEntities {

  private static final Faker FAKER = new Faker();
  private static final String TAG_PATH = "/Jira/";

  public TimeGroup randomTimeGroup() {
    final List<TimeRow> timeRows = randomEntities(() -> randomTimeRow(), 1, 10);

    return new TimeGroup()
        .callerKey(FAKER.bothify("#?#?#?#?#?"))
        .groupId(UUID.randomUUID().toString())
        .description(FAKER.gameOfThrones().quote())
        .totalDurationSecs(timeRows.stream().mapToInt(TimeRow::getDurationSecs).sum())
        .groupName(FAKER.gameOfThrones().city())
        .tags(randomEntities(() -> randomTag(TAG_PATH), 1, 3))
        .user(randomUser())
        .timeRows(timeRows)
        .narrativeType(randomEnum(TimeGroup.NarrativeTypeEnum.class))
        .durationSplitStrategy(randomEnum(TimeGroup.DurationSplitStrategyEnum.class));
  }

  public Tag randomTag(final String path) {
    return new Tag()
        .path(path)
        .name(FAKER.letterify("??-") + FAKER.number().numberBetween(1000, 9999))
        .description(FAKER.gameOfThrones().character());
  }

  public User randomUser() {
    final String firstName = FAKER.name().firstName();
    final String lastName = FAKER.name().lastName();
    return new User()
        .name(firstName + " " + lastName)
        .email(FAKER.internet().emailAddress(firstName))
        .externalId(FAKER.internet().emailAddress(firstName + "." + lastName))
        .businessRole(FAKER.company().profession())
        .experienceWeightingPercent(FAKER.random().nextInt(0, 100));
  }

  public TimeRow randomTimeRow() {
    return new TimeRow()
        .activity(FAKER.company().catchPhrase())
        .activityHour(2018110100 + FAKER.random().nextInt(1, 23))
        .durationSecs(FAKER.random().nextInt(120, 600))
        .submittedDate(Long.valueOf(FAKER.numerify("20180#1#1#5#2####")))
        .modifier(FAKER.gameOfThrones().dragon())
        .source(randomEnum(TimeRow.SourceEnum.class));
  }

  public Issue randomIssue() {
    final Tag tag = randomTag(TAG_PATH);
    return randomIssue(tag.getName());
  }

  public Issue randomIssue(final String key) {
    final String[] tagParts = key.split("-");
    Preconditions.checkArgument(tagParts.length == 2);
    return ImmutableIssue
        .builder()
        .id(FAKER.random().nextInt(1, 999999))
        .projectKey(tagParts[0])
        .issueNumber(tagParts[1])
        .summary(FAKER.lorem().characters(0, 100))
        .timeSpent(0L)
        .build();
  }

  public List<Issue> randomIssues(int count) {
    return randomEntities(this::randomIssue, count, count);
  }

  public Worklog randomWorklog(ZoneId zoneId) {
    return ImmutableWorklog.builder()
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
