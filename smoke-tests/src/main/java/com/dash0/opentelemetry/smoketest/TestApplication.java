/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

/**
 * A minimal application for the agent to attach to. It must not depend on the OpenTelemetry API.
 *
 * @param args optionally one value, the number of milliseconds to stay alive before exiting
 */
public final class TestApplication {

  /** Printed as the last thing {@code main} does, and nowhere else. */
  public static final String COMPLETION_MARKER = "test-application-completed";

  private TestApplication() {}

  public static void main(String[] args) throws InterruptedException {
    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println(COMPLETION_MARKER);
  }
}
