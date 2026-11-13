/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Starts a real JVM with the built agent attached, and proves it accepts the agent: the manifest
 * entry points resolve, the premain runs, and the application reaches the end of {@code main}.
 */
class AgentAttachTest {

  @Test
  void attachesToAJvmAndLetsTheApplicationComplete() throws Exception {
    String output = withoutAnyExporter().run();

    assertThat(output).contains(TestApplication.COMPLETION_MARKER);
  }

  @Test
  void reportsTheDash0ImplementationVersionAtStartup() throws Exception {
    String output = withoutAnyExporter().run();

    assertThat(output).as("agent startup output was:%n%s", output).contains("dash0-");
  }

  private static InstrumentedJvm withoutAnyExporter() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.javaagent.debug=true",
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none");
  }
}
