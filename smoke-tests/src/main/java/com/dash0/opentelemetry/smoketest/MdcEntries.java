/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

/**
 * The MDC keys and values the three MDC applications set, and the message they log.
 *
 * <p>One key is an ordinary identifier and the other three carry a credential-shaped key, one per
 * spelling the deny list has to cover.
 */
public final class MdcEntries {

  /** The logged message, which is never an MDC value. */
  public static final String MESSAGE = "mdc-application-logged-this";

  /** An MDC key this distribution captures. */
  public static final String ORDINARY_KEY = "tenant.id";

  /** The value of {@link #ORDINARY_KEY}. */
  public static final String ORDINARY_VALUE = "tenant-4711";

  /** A credential-shaped MDC key spelled in lower case. */
  public static final String LOWER_CASE_KEY = "session-token";

  /** A credential-shaped MDC key spelled in camel case. */
  public static final String CAMEL_CASE_KEY = "authToken";

  /** A credential-shaped MDC key spelled in upper case. */
  public static final String UPPER_CASE_KEY = "X-AUTHORIZATION";

  /** The value of all three credential-shaped keys. */
  public static final String CREDENTIAL_VALUE = "credential-value-that-must-not-be-exported";

  private MdcEntries() {}
}
