/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.testutils;

import com.google.common.base.Preconditions;

import com.github.javafaker.Faker;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.wisetime.connector.jira.models.ImmutableIssue;
import io.wisetime.connector.jira.models.Issue;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;

/**
 * @author shane.xie@practiceinsight.io
 */
public class FakeEntities {

  private static final Faker faker = new Faker();
  private static final String TAG_PATH = "/Jira";

  public TimeGroup randomTimeGroup() {
    final List<TimeRow> timeRows = randomEntities(() -> randomTimeRow(), 1, 10);

    return new TimeGroup()
        .callerKey(faker.bothify("#?#?#?#?#?"))
        .groupId(UUID.randomUUID().toString())
        .description(faker.gameOfThrones().quote())
        .totalDurationSecs(timeRows.stream().mapToInt(TimeRow::getDurationSecs).sum())
        .groupName(faker.gameOfThrones().city())
        .tags(randomEntities(() -> randomTag(TAG_PATH), 1, 3))
        .user(randomUser())
        .timeRows(timeRows)
        .narrativeType(randomEnum(TimeGroup.NarrativeTypeEnum.class))
        .durationSplitStrategy(randomEnum(TimeGroup.DurationSplitStrategyEnum.class));
  }

  public Tag randomTag(final String path) {
    return new Tag()
        .name(faker.bothify("??-####", true))
        .description(faker.gameOfThrones().character());
  }

  public User randomUser() {
    final String firstName = faker.name().firstName();
    final String lastName = faker.name().lastName();
    return new User()
        .name(firstName + " " + lastName)
        .email(faker.internet().emailAddress(firstName))
        .externalId(faker.internet().emailAddress(firstName + "." + lastName))
        .businessRole(faker.company().profession())
        .experienceWeightingPercent(faker.random().nextInt(0, 100));
  }

  public TimeRow randomTimeRow() {
    return new TimeRow()
        .activity(faker.company().catchPhrase())
        .activityHour(2018110100 + faker.random().nextInt(1, 23))
        .durationSecs(faker.random().nextInt(120, 600))
        .submittedDate(Long.valueOf(faker.numerify("20180#1#1#5#2####")))
        .modifier(faker.gameOfThrones().dragon())
        .source(randomEnum(TimeRow.SourceEnum.class));
  }

  public Issue randomIssue() {
    final Tag tag = randomTag("/Jira");
    return randomIssue(tag.getName());
  }

  public Issue randomIssue(final String key) {
    final String[] tagParts = key.split("-");
    Preconditions.checkArgument(tagParts.length == 2);
    return ImmutableIssue
        .builder()
        .id(faker.random().nextInt(1, 999999))
        .projectKey(tagParts[0])
        .issueNumber(tagParts[1])
        .summary(faker.hitchhikersGuideToTheGalaxy().quote())
        .timeSpent(0L)
        .build();
  }

  private <T> List<T> randomEntities(final Supplier<T> supplier, final int min, final int max) {
    return IntStream
        .range(0, faker.random().nextInt(min, max))
        .mapToObj(i -> supplier.get())
        .collect(Collectors.toList());
  }

  private static <T extends Enum<?>> T randomEnum(Class<T> clazz){
    final int index = faker.random().nextInt(clazz.getEnumConstants().length);
    return clazz.getEnumConstants()[index];
  }
}
