/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.jira.config;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @author shane.xie@practiceinsight.io
 */
@Retention(RetentionPolicy.RUNTIME)
@BindingAnnotation
public @interface TagUpsertPath {
}

