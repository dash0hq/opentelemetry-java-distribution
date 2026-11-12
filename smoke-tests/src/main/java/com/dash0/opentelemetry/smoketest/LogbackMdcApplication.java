/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** An application that logs {@link MdcEntries#MESSAGE} through Logback with the MDC set. */
public final class LogbackMdcApplication {

  private LogbackMdcApplication() {}

  public static void main(String[] args) throws InterruptedException {
    MDC.put(MdcEntries.ORDINARY_KEY, MdcEntries.ORDINARY_VALUE);
    MDC.put(MdcEntries.LOWER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    MDC.put(MdcEntries.CAMEL_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    MDC.put(MdcEntries.UPPER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);

    LoggerFactory.getLogger(LogbackMdcApplication.class).info(MdcEntries.MESSAGE);

    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(TestApplication.COMPLETION_MARKER);
  }
}
