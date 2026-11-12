/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;

/**
 * An application that logs {@link MdcEntries#MESSAGE} through Log4j with the context data set.
 *
 * <p>Log4j calls MDC the thread context, and the agent's selector for it is still named {@code
 * mdc-attributes}.
 */
public final class Log4jMdcApplication {

  private Log4jMdcApplication() {}

  public static void main(String[] args) throws InterruptedException {
    ThreadContext.put(MdcEntries.ORDINARY_KEY, MdcEntries.ORDINARY_VALUE);
    ThreadContext.put(MdcEntries.LOWER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    ThreadContext.put(MdcEntries.CAMEL_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    ThreadContext.put(MdcEntries.UPPER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);

    LogManager.getLogger(Log4jMdcApplication.class).info(MdcEntries.MESSAGE);

    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(TestApplication.COMPLETION_MARKER);
  }
}
