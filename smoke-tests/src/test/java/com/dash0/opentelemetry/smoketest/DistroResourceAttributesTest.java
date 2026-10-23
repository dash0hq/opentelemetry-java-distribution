/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Asserts on the resource attributes a real instrumented JVM actually exports.
 *
 * <p>This is the only test in the repository that can catch resource-provider ordering going wrong.
 * The agent ships its own provider setting {@code telemetry.distro.name}; if the Dash0 provider
 * does not sort after it, the Dash0 value is overwritten and every signal is attributed to the
 * upstream agent instead. Nothing logs a warning when that happens, the packaging tests still pass,
 * and the unit tests still pass, because in isolation the Dash0 provider returns exactly the right
 * resource.
 *
 * <p>The metric exporter is used as the read-back channel because it prints the full resource, and
 * because the agent produces JVM runtime metrics on its own without the application taking part.
 */
class DistroResourceAttributesTest {

  @Test
  void exportsTheDash0DistroNameRatherThanUpstreams() throws Exception {
    String exported = exportingTheResourceToTheLog();

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-java-instrumentation\"");
  }

  @Test
  void exportsTheDistributionVersionRatherThanTheAgentManifestVersion() throws Exception {
    String exported = exportingTheResourceToTheLog();

    // Upstream's provider reports the jar manifest's Implementation-Version,
    // which is the combined "dash0-<distro>-otel-<upstream>" string. The Dash0
    // provider reports the distribution version on its own.
    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains(
            "telemetry.distro.version=\"" + System.getProperty("dash0.distro.version") + "\"");
  }

  private static String exportingTheResourceToTheLog() throws Exception {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            // The logging metric exporter prints the resource on every export.
            "-Dotel.metrics.exporter=logging",
            "-Dotel.metric.export.interval=500",
            "-Dotel.service.name=smoke-test")
        // Stay alive long enough for at least one periodic export.
        .exitingAfter(4000)
        .run();
  }
}
