/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import org.jboss.logmanager.Logger;
import org.jboss.logmanager.MDC;

/**
 * An application that logs {@link MdcEntries#MESSAGE} through JBoss Log Manager with the MDC set.
 *
 * <p>It needs {@code -Djava.util.logging.manager=org.jboss.logmanager.LogManager} on the command
 * line, which is how WildFly and JBoss EAP install this log manager.
 */
public final class JbossLogManagerMdcApplication {

  private JbossLogManagerMdcApplication() {}

  public static void main(String[] args) throws InterruptedException {
    MDC.put(MdcEntries.ORDINARY_KEY, MdcEntries.ORDINARY_VALUE);
    MDC.put(MdcEntries.LOWER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    MDC.put(MdcEntries.CAMEL_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);
    MDC.put(MdcEntries.UPPER_CASE_KEY, MdcEntries.CREDENTIAL_VALUE);

    Logger.getLogger(JbossLogManagerMdcApplication.class.getName()).info(MdcEntries.MESSAGE);

    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(TestApplication.COMPLETION_MARKER);
  }
}
