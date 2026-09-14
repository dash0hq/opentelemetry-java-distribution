/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
  void exportsTheDash0DistroNameRatherThanUpstreams() throws IOException, InterruptedException {
    String exported = runInstrumentedApplicationAndCaptureExportedTelemetry();

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-java-instrumentation\"");
  }

  @Test
  void exportsTheDistributionVersionRatherThanTheAgentManifestVersion()
      throws IOException, InterruptedException {
    String exported = runInstrumentedApplicationAndCaptureExportedTelemetry();

    // Upstream's provider reports the jar manifest's Implementation-Version,
    // which is the combined "dash0-<distro>-otel-<upstream>" string. The Dash0
    // provider reports the distribution version on its own.
    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.version=\"" + expectedDistroVersion() + "\"");
  }

  private static String expectedDistroVersion() {
    return System.getProperty("dash0.distro.version");
  }

  private static String runInstrumentedApplicationAndCaptureExportedTelemetry()
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("dash0.agent.jar"));
    command.add("-Dotel.traces.exporter=none");
    command.add("-Dotel.logs.exporter=none");
    // The logging metric exporter prints the resource on every export.
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.metric.export.interval=500");
    command.add("-Dotel.service.name=smoke-test");
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());
    // Stay alive long enough for at least one periodic export.
    command.add("4000");

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int read;
    while ((read = process.getInputStream().read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("the instrumented JVM did not exit within two minutes");
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }
}
