/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * An application that logs one message through Logback with four MDC entries set.
 *
 * <p>One entry is an ordinary identifier and the other three carry a credential-shaped key, one per
 * spelling the deny list has to cover.
 */
public final class MdcLoggingApplication {

  /** The logged message, which is never an MDC value. */
  public static final String MESSAGE = "mdc-logging-application-logged-this";

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

  private MdcLoggingApplication() {}

  public static void main(String[] args) throws InterruptedException {
    MDC.put(ORDINARY_KEY, ORDINARY_VALUE);
    MDC.put(LOWER_CASE_KEY, CREDENTIAL_VALUE);
    MDC.put(CAMEL_CASE_KEY, CREDENTIAL_VALUE);
    MDC.put(UPPER_CASE_KEY, CREDENTIAL_VALUE);

    LoggerFactory.getLogger(MdcLoggingApplication.class).info(MESSAGE);

    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(TestApplication.COMPLETION_MARKER);
  }
}
