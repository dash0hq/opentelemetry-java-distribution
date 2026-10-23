/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Starts a real JVM with the built agent attached.
 *
 * <p>The packaging tests prove the artifact has the right shape. This proves the JVM accepts it:
 * that the manifest entry points resolve, the agent's premain runs, and an application that knows
 * nothing about OpenTelemetry still reaches the end of {@code main}.
 *
 * <p>Asserting on what the process exports is the job of {@code DistroResourceAttributesTest} and
 * {@code PackageResourceAttributesTest}, and asserting on what a real collector received is the job
 * of {@code examples/dash0-distro-java/verify.sh}.
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

    // The agent's version logger reports the Implementation-Version from the jar
    // manifest. Seeing the "dash0-" prefix proves both that the agent actually
    // started and that our manifest, rather than upstream's, is the one in force.
    assertThat(output).as("agent startup output was:%n%s", output).contains("dash0-");
  }

  private static InstrumentedJvm withoutAnyExporter() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.javaagent.debug=true",
            // Nothing should try to reach a collector during a packaging smoke test.
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none");
  }
}
