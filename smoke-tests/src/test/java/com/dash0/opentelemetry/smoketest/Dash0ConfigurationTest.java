/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Asserts that what this distribution configures reaches a real instrumented JVM and changes what
 * it does.
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
  private static final String AMBIENT_HOST = "ambient-environment.invalid";

  private static final String INSTRUMENTED_METHOD = TestApplication.class.getName() + "[main]";

  /** The {@code code} attribute the stable semantic conventions emit. */
  private static final String STABLE_CODE_ATTRIBUTE =
      "code.function.name=" + TestApplication.class.getName() + ".main";

  /** The {@code code} attribute the superseded semantic conventions emit. */
  private static final String OLD_CODE_ATTRIBUTE =
      "code.namespace=" + TestApplication.class.getName();

  @Test
  void theStandardEndpointTakesPrecedenceOverTheCollectorBaseUrl() throws Exception {
    Map<String, String> environment = new HashMap<>();
    environment.put("DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318");
    environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://" + OTEL_HOST + ":4318");

    String output = exportingMetricsOverOtlp().withEnvironment(environment).run();

    assertThat(output)
        .as(
            "DASH0_OTEL_COLLECTOR_BASE_URL is the host-wide default that one"
                + " /etc/opentelemetry/injector/default_env.conf sets, so a workload's own"
                + " OTEL_EXPORTER_OTLP_ENDPOINT beats it, matching the Python and Ruby"
                + " distributions. The JVM's output was:%n%s",
            output)
        .contains(OTEL_HOST)
        .doesNotContain(DASH0_HOST);
  }

  @Test
  void theCollectorBaseUrlAppliesWhenTheStandardEndpointIsAbsent() throws Exception {
    String output =
        exportingMetricsOverOtlp()
            .withEnvironmentVariable(
                "DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318")
            .run();

    assertThat(output)
        .as(
            "the control for the test above: without it, that one would also pass if the Dash0"
                + " setting had stopped working altogether. The JVM's output was:%n%s",
            output)
        .contains(DASH0_HOST);
  }

  @Test
  void theLaunchedJvmIsNotReachedByTheAmbientEnvironment() throws Exception {
    assertThat(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
        .as(
            "smoke-tests/build.gradle.kts sets this on the test JVM on purpose, so that the whole"
                + " suite runs under the environment a developer working on OpenTelemetry has"
                + " exported. Without it this test proves nothing, so it is checked rather than"
                + " assumed.")
        .isEqualTo("http://" + AMBIENT_HOST + ":4318");
    assertThat(System.getenv("DASH0_DISABLE")).isEqualTo("true");

    String output =
        exportingMetricsOverOtlp()
            .withEnvironmentVariable(
                "DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318")
            .run();

    assertThat(output)
        .as(
            "the inherited OTEL_EXPORTER_OTLP_ENDPOINT would win over the collector base URL, and"
                + " the inherited DASH0_DISABLE would stop the export entirely. The JVM's output"
                + " was:%n%s",
            output)
        .contains(DASH0_HOST)
        .doesNotContain(AMBIENT_HOST);
  }

  @Test
  void aTrailingSlashOnTheCollectorBaseUrlDoesNotReachTheExporter() throws Exception {
    String output =
        exportingMetricsOverOtlp()
            .withEnvironmentVariable(
                "DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST + ":4318/")
            .run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(DASH0_HOST)
        .doesNotContain(DASH0_HOST + ":4318//");
  }

  @Test
  void disableStopsTelemetry() throws Exception {
    String output =
        exportingMetricsToTheLog().withEnvironmentVariable("DASH0_DISABLE", "true").run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .doesNotContain("LoggingMetricExporter");
  }

  @Test
  void telemetryFlowsWhenDisableIsAbsent() throws Exception {
    String output = exportingMetricsToTheLog().run();

    assertThat(output).as("the JVM's output was:%n%s", output).contains("LoggingMetricExporter");
  }

  @Test
  void aDisableValueOtherThanTrueLeavesTelemetryFlowing() throws Exception {
    String output = exportingMetricsToTheLog().withEnvironmentVariable("DASH0_DISABLE", "1").run();

    assertThat(output).as("the JVM's output was:%n%s", output).contains("LoggingMetricExporter");
  }

  @Test
  void theSuppliedSamplerIsAValueTheAgentAccepts() throws Exception {
    String output =
        InstrumentedJvm.runningTheTestApplication()
            .withJvmArguments(
                "-Dotel.traces.exporter=none",
                "-Dotel.metrics.exporter=none",
                "-Dotel.logs.exporter=none",
                "-Dotel.service.name=smoke-test")
            .run();

    assertThat(output)
        .as(
            "the distribution supplies otel.traces.sampler itself, and the agent answers a value it"
                + " does not know by failing autoconfiguration, which leaves the process running"
                + " and reporting nothing. The JVM's output was:%n%s",
            output)
        .doesNotContain("Unrecognized value for otel.traces.sampler");
  }

  @Test
  void theSuppliedSemconvStabilityOptInReachesTheInstrumentation() throws Exception {
    String output = recordingAMethodSpan().run();

    assertThat(output)
        .as(
            "the agent resolves otel.semconv-stability.opt-in through SemconvSelectionResolver,"
                + " which reads it with SystemProperty.getString and so sees neither"
                + " addPropertiesSupplier nor addPropertiesCustomizer. Either hook would leave the"
                + " old attribute names in place with nothing reporting it. The JVM's output"
                + " was:%n%s",
            output)
        .contains(STABLE_CODE_ATTRIBUTE)
        .doesNotContain(OLD_CODE_ATTRIBUTE);
  }

  @Test
  void anEnvironmentVariableSemconvStabilityOptInBeatsTheSuppliedOne() throws Exception {
    String output =
        recordingAMethodSpan()
            .withEnvironmentVariable("OTEL_SEMCONV_STABILITY_OPT_IN", "database")
            .run();

    assertThat(output)
        .as(
            "naming only database leaves code on the old conventions, which is what proves the"
                + " workload's own value replaced the supplied one rather than being merged into"
                + " it. The JVM's output was:%n%s",
            output)
        .contains(OLD_CODE_ATTRIBUTE)
        .doesNotContain(STABLE_CODE_ATTRIBUTE);
  }

  @Test
  void aSystemPropertySemconvStabilityOptInBeatsTheSuppliedOne() throws Exception {
    String output =
        recordingAMethodSpan().withJvmArguments("-Dotel.semconv-stability.opt-in=database").run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(OLD_CODE_ATTRIBUTE)
        .doesNotContain(STABLE_CODE_ATTRIBUTE);
  }

  /**
   * An instrumented JVM that logs one span for {@code TestApplication.main}, carrying the {@code
   * code} attributes the {@code code} entry of {@code otel.semconv-stability.opt-in} selects.
   */
  private static InstrumentedJvm recordingAMethodSpan() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=logging",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.service.name=smoke-test",
            "-Dotel.instrumentation.methods.include=" + INSTRUMENTED_METHOD);
  }

  private static InstrumentedJvm exportingMetricsToTheLog() {
    return instrumentedApplication()
        .withJvmArguments("-Dotel.metrics.exporter=logging", "-Dotel.metric.export.interval=500");
  }

  private static InstrumentedJvm exportingMetricsOverOtlp() {
    return instrumentedApplication().withJvmArguments("-Dotel.metric.export.interval=500");
  }

  private static InstrumentedJvm instrumentedApplication() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.service.name=smoke-test")
        .exitingAfter(3000);
  }
}
