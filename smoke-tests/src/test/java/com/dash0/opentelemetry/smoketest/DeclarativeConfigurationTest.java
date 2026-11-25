/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the Dash0 customisations reach a real instrumented JVM when {@code OTEL_CONFIG_FILE}
 * is set. See {@code docs/configuration.md}.
 */
class DeclarativeConfigurationTest {

  private static final String DASH0_HOST = "declarative-collector-base-url.invalid";
  private static final String OTEL_HOST = "declarative-exporter-endpoint.invalid";
  private static final String EXPORTER_THREAD = "[OkHttp ";
  private static final String TRACEPARENT = TestApplication.RECEIVED_HEADER + "traceparent: 00-";
  private static final Pattern XRAY_WITH_PARENT =
      Pattern.compile(
          Pattern.quote(TestApplication.RECEIVED_HEADER)
              + "X-Amzn-Trace-Id: Root=1-[0-9a-f]{8}-[0-9a-f]{24};Parent=[0-9a-f]{16};Sampled=1");

  @Test
  void theAgentStartsAndExportsWhatTheFileAsksFor() throws Exception {
    String exported = configuredFrom("/declarative-config.yaml").run();

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .contains("service.name=\"declarative-smoke-test\"");
  }

  @Test
  void theDash0DistroAttributesArePresent() throws Exception {
    String exported = configuredFrom("/declarative-config.yaml").run();

    assertThat(exported)
        .as(
            "Dash0DeclarativeConfigurationCustomizerProvider writes them into resource.attributes,"
                + " which ResourceFactory applies after every detector, so upstream's own value"
                + " loses. The JVM's output was:%n%s",
            exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("telemetry.distro.version=")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-javaagent\"");
  }

  @Test
  void theUpstreamHostProcessAndServiceDetectorsRunToo() throws Exception {
    String exported = configuredFrom("/declarative-config.yaml").run();

    assertThat(exported)
        .as(
            "the customizer names the host, process and service detectors in"
                + " resource.detection/development, which the file itself does not. host.id comes"
                + " with host.name, and the environment path leaves it off. The JVM's output"
                + " was:%n%s",
            exported)
        .contains("host.name=")
        .contains("host.id=")
        .contains("process.pid=")
        .contains("service.instance.id=");
  }

  @Test
  void dash0DisableSwitchesTheSdkOff() throws Exception {
    String exported =
        configuredFrom("/declarative-config.yaml")
            .withEnvironmentVariable("DASH0_DISABLE", "true")
            .run();

    assertThat(exported)
        .as(
            "the customizer sets the model's disabled flag, which makes the SDK a no-op. The"
                + " application still ran to completion, which InstrumentedJvm.run checks, so this"
                + " is a silenced SDK and not a dead agent. The JVM's output was:%n%s",
            exported)
        .doesNotContain("service.name=\"declarative-smoke-test\"");
  }

  @Test
  void theCollectorBaseUrlReachesAnExporterTheFileLeftWithoutAnEndpoint() throws Exception {
    String exported =
        configuredFrom("/declarative-config-otlp.yaml")
            .withEnvironmentVariable("DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST)
            .run();

    assertThat(exported)
        .as(
            "the export failure names the host the exporter was pointed at. The signal path the"
                + " customizer appends is asserted in"
                + " Dash0DeclarativeConfigurationCustomizerProviderTest, since the exporter thread"
                + " name truncates the URL. The JVM's output was:%n%s",
            exported)
        .contains(DASH0_HOST);
  }

  @Test
  void theStandardEndpointSuppressesTheCollectorBaseUrlAndConfiguresNothingItself()
      throws Exception {
    Map<String, String> environment = new HashMap<>();
    environment.put("DASH0_OTEL_COLLECTOR_BASE_URL", "http://" + DASH0_HOST);
    environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://" + OTEL_HOST + ":4318");

    String exported =
        configuredFrom("/declarative-config-otlp.yaml").withEnvironment(environment).run();

    assertThat(exported)
        .as(
            "a declaratively configured SDK reads no OTEL_EXPORTER_OTLP_* variable, so the"
                + " exporter reaches neither host and keeps its own specification default. The"
                + " presence asserted is the customizer's warning, which a dead agent would not"
                + " print either. The fallback URL is asserted inside that warning text and"
                + " never against the exporter's own thread, since the exporter names the URL"
                + " in the log only when the connection fails, and port 4318 is the standard"
                + " OTLP/HTTP receiver port that a developer may well have something listening"
                + " on. The absences are scoped to the exporter thread, since the warning quotes"
                + " the value of OTEL_EXPORTER_OTLP_ENDPOINT. The JVM's output was:%n%s",
            exported)
        .contains("so DASH0_OTEL_COLLECTOR_BASE_URL is left unapplied")
        .contains("http://localhost:4318")
        .doesNotContain(EXPORTER_THREAD + "http://" + DASH0_HOST)
        .doesNotContain(EXPORTER_THREAD + "http://" + OTEL_HOST);
  }

  @Test
  void thePropagatorsDefaultToTracecontextBaggageAndXray() throws Exception {
    String output = sendingARequestConfiguredFrom("/declarative-config-traces.yaml").run();

    assertThat(output)
        .as(
            "a file that declares no propagator leaves OpenTelemetrySdkBuilder on its noop"
                + " default, which writes no trace header at all. The JVM's output was:%n%s",
            output)
        .contains(TRACEPARENT)
        .containsPattern(XRAY_WITH_PARENT);
  }

  @Test
  void theSamplerDefaultStartsNoTracingForAFileThatDeclaresNoTracerProvider() throws Exception {
    String output = sendingARequestConfiguredFrom("/declarative-config.yaml").run();

    assertThat(output)
        .as(
            "SdkTracerProvider.tracerBuilder answers with a noop tracer while the provider holds"
                + " no span processor, so the always_on sampler the customizer writes into the"
                + " node it creates is never consulted and no span is started. The presence"
                + " asserted is the Dash0 resource attribute, which an agent that failed to start"
                + " would not print either. The JVM's output was:%n%s",
            output)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain(TRACEPARENT)
        .doesNotContain("X-Amzn-Trace-Id");
  }

  @Test
  void thePropagatorsTheFileDeclaresAreKept() throws Exception {
    String output = sendingARequestConfiguredFrom("/declarative-config-propagator.yaml").run();

    assertThat(output)
        .as(
            "the file is the configuration under OTEL_CONFIG_FILE, so a declared composite wins"
                + " over the Dash0 default. The JVM's output was:%n%s",
            output)
        .contains(TestApplication.RECEIVED_HEADER + "X-B3-TraceId: ")
        .doesNotContain(TRACEPARENT)
        .doesNotContain("X-Amzn-Trace-Id");
  }

  @Test
  void theServiceDetectorMakesOtelServiceNameTakeEffect() throws Exception {
    String exported =
        configuredFrom("/declarative-config-no-service-name.yaml")
            .withEnvironmentVariable("OTEL_SERVICE_NAME", "from-otel-service-name")
            .run();

    assertThat(exported)
        .as(
            "ServiceResourceDetector reads otel.service.name off a DefaultConfigProperties built"
                + " over the real environment, so the one detector the customizer names for"
                + " service.instance.id also makes this variable live in a mode that ignores"
                + " OTEL_RESOURCE_ATTRIBUTES. The stock upstream agent exports"
                + " unknown_service:java for the same file and the same variable. The JVM's"
                + " output was:%n%s",
            exported)
        .contains("service.name=\"from-otel-service-name\"")
        .contains("service.instance.id=");
  }

  @Test
  void theFilesServiceNameBeatsOtelServiceName() throws Exception {
    String exported =
        configuredFrom("/declarative-config.yaml")
            .withEnvironmentVariable("OTEL_SERVICE_NAME", "from-otel-service-name")
            .run();

    assertThat(exported)
        .as(
            "resource.attributes is applied after every detector, which is the reverse of the"
                + " environment path, where EnvironmentResourceProvider at order 2147483646 wins."
                + " The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"")
        .doesNotContain("from-otel-service-name");
  }

  @Test
  void theExtensionJarContributesTheSameThingsAsTheAgent() throws Exception {
    String exported =
        InstrumentedJvm.runningTheTestApplication()
            .attaching(Paths.get(System.getProperty("dash0.upstream.agent.jar")))
            .withJvmArguments(
                "-Dotel.javaagent.extensions=" + System.getProperty("dash0.extension.jar"))
            .withEnvironmentVariable("OTEL_CONFIG_FILE", fixture("/declarative-config.yaml"))
            .exitingAfter(4000)
            .run();

    assertThat(exported)
        .as(
            "that the component loader reaches the extension class loader on the declarative path"
                + " is a separate question from the agent jar, so both delivery shapes are"
                + " exercised. The JVM's output was:%n%s",
            exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("host.name=");
  }

  private static InstrumentedJvm configuredFrom(String fixture) throws URISyntaxException {
    return InstrumentedJvm.runningTheTestApplication()
        .withEnvironmentVariable("OTEL_CONFIG_FILE", fixture(fixture))
        .exitingAfter(3000);
  }

  private static InstrumentedJvm sendingARequestConfiguredFrom(String fixture)
      throws URISyntaxException {
    return configuredFrom(fixture).withJvmArguments("-D" + TestApplication.SEND_REQUEST + "=true");
  }

  private static String fixture(String name) throws URISyntaxException {
    return Paths.get(DeclarativeConfigurationTest.class.getResource(name).toURI()).toString();
  }
}
