/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

/**
 * A minimal application for the agent to attach to.
 *
 * <p>It does nothing except prove the JVM reached and completed {@code main} with the agent
 * installed. It must not depend on the OpenTelemetry API: the point is to instrument an application
 * that knows nothing about OpenTelemetry.
 *
 * @param args optionally one value, the number of milliseconds to stay alive before exiting, so
 *     that a periodic exporter has time to run at least once
 */
public final class TestApplication {

  private TestApplication() {}

  public static void main(String[] args) throws InterruptedException {
    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
    System.out.println("test-application-completed");
  }
}
