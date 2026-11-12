/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The MDC keys and values the three MDC applications set, and the message they log.
 *
 * <p>One key is an ordinary identifier. The other twelve are credential-shaped, one for each
 * pattern in the distribution's deny list, and each matched by that pattern alone.
 */
public final class MdcEntries {

  /** The logged message, which is never an MDC value. */
  public static final String MESSAGE = "mdc-application-logged-this";

  /** An MDC key this distribution captures. */
  public static final String ORDINARY_KEY = "tenant.id";

  /** The value of {@link #ORDINARY_KEY}. */
  public static final String ORDINARY_VALUE = "tenant-4711";

  /** The credential-shaped key matched by the {@code *AUTHORIZATION*} pattern. */
  public static final String UPPER_CASE_AUTHORIZATION_KEY = "X-AUTHORIZATION";

  /** The value of every key in {@link #CREDENTIAL_KEYS}. */
  public static final String CREDENTIAL_VALUE = "credential-value-that-must-not-be-exported";

  /** One credential-shaped key per deny-list pattern, in the order the patterns are written. */
  public static final List<String> CREDENTIAL_KEYS =
      Collections.unmodifiableList(
          Arrays.asList(
              "session-token",
              "authToken",
              "REFRESH_TOKEN",
              "client-secret",
              "clientSecret",
              "CLIENT_SECRET",
              "user-password",
              "userPassword",
              "DB_PASSWORD",
              "authorization",
              "proxyAuthorization",
              UPPER_CASE_AUTHORIZATION_KEY));

  private MdcEntries() {}
}
