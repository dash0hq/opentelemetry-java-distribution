/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the Dash0 settings reach a real instrumented JVM and change what it does.
 *
 * <p>The unit tests in {@code :custom} prove that the customizer returns the right map. They cannot
 * prove that the agent ever invokes it, that the environment variable name resolves to the property
 * name the customizer reads, or that the value survives into the exporter. Each of those fails
 * silently: the agent starts, instruments normally, and quietly ignores the setting.
 *
 * <p>The unroutable hosts below use the reserved {@code .invalid} top-level domain, which cannot
 * resolve anywhere, so the export failure naming a host is proof of which endpoint was configured
 * without needing a collector to run.
 */
class Dash0ConfigurationTest {

  private static final String DASH0_HOST = "dash0-collector-base-url.invalid";
  private static final String OTEL_HOST = "otel-exporter-endpoint.invalid";

  @Test
  void collectorBaseUrlTakesPrecedenceOverTheStandardEndpoint() throws Exception {
    Map<String, String> environment = new HashMap<>();
    environment.put("DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318");
    environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://" + OTEL_HOST + ":4318");

    String output = runInstrumentedApplication(environment, exportingMetricsOverOtlp());

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(DASH0_HOST)
        .doesNotContain(OTEL_HOST);
  }

  @Test
  void aTrailingSlashOnTheCollectorBaseUrlDoesNotReachTheExporter() throws Exception {
    Map<String, String> environment =
        Collections.singletonMap(
            "DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318/");

    String output = runInstrumentedApplication(environment, exportingMetricsOverOtlp());

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(DASH0_HOST)
        .doesNotContain(DASH0_HOST + ":4318//");
  }

  @Test
  void disableStopsTelemetry() throws Exception {
    String output =
        runInstrumentedApplication(
            Collections.singletonMap("DASH0_DISABLE", "true"), exportingMetricsToTheLog());

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .doesNotContain("LoggingMetricExporter");
  }

  @Test
  void telemetryFlowsWhenDisableIsAbsent() throws Exception {
    String output = runInstrumentedApplication(Collections.emptyMap(), exportingMetricsToTheLog());

    assertThat(output).as("the JVM's output was:%n%s", output).contains("LoggingMetricExporter");
  }

  @Test
  void aDisableValueOtherThanTrueLeavesTelemetryFlowing() throws Exception {
    String output =
        runInstrumentedApplication(
            Collections.singletonMap("DASH0_DISABLE", "1"), exportingMetricsToTheLog());

    assertThat(output).as("the JVM's output was:%n%s", output).contains("LoggingMetricExporter");
  }

  private static List<String> exportingMetricsToTheLog() {
    return Arrays.asList(
        "-Dotel.traces.exporter=none",
        "-Dotel.logs.exporter=none",
        "-Dotel.metrics.exporter=logging",
        "-Dotel.metric.export.interval=500");
  }

  private static List<String> exportingMetricsOverOtlp() {
    return Arrays.asList(
        "-Dotel.traces.exporter=none",
        "-Dotel.logs.exporter=none",
        "-Dotel.metric.export.interval=500");
  }

  private static String runInstrumentedApplication(
      Map<String, String> environment, List<String> jvmArguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("dash0.agent.jar"));
    command.addAll(jvmArguments);
    command.add("-Dotel.service.name=smoke-test");
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());
    command.add("3000");

    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().putAll(environment);

    Process process = builder.start();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
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
