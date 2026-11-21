/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Asserts which trace headers a real instrumented JVM writes on an outgoing HTTP request.
 *
 * <p>The X-Ray propagator is the one the upstream agent bundles, so this also checks that it is
 * reachable under the name {@code xray} in both delivery shapes.
 */
class PropagatorsTest {

  private static final String TRACEPARENT = TestApplication.RECEIVED_HEADER + "traceparent: 00-";
  private static final Pattern XRAY_WITH_PARENT =
      Pattern.compile(
          Pattern.quote(TestApplication.RECEIVED_HEADER)
              + "X-Amzn-Trace-Id: Root=1-[0-9a-f]{8}-[0-9a-f]{24};Parent=[0-9a-f]{16};Sampled=1");

  @Test
  void writesTracecontextAndXrayByDefault() throws Exception {
    String output = sendingARequest().run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(TRACEPARENT)
        .containsPattern(XRAY_WITH_PARENT);
  }

  @Test
  void honoursTheWorkloadsOwnPropagators() throws Exception {
    String output = sendingARequest().withEnvironmentVariable("OTEL_PROPAGATORS", "b3multi").run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(TestApplication.RECEIVED_HEADER + "X-B3-TraceId: ")
        .doesNotContain(TRACEPARENT)
        .doesNotContain("X-Amzn-Trace-Id");
  }

  @Test
  void writesTracecontextAndXrayByDefaultThroughTheExtension() throws Exception {
    String output =
        sendingARequest()
            .attaching(Paths.get(System.getProperty("dash0.upstream.agent.jar")))
            .withJvmArguments(
                "-Dotel.javaagent.extensions=" + System.getProperty("dash0.extension.jar"))
            .run();

    assertThat(output)
        .as("the JVM's output was:%n%s", output)
        .contains(TRACEPARENT)
        .containsPattern(XRAY_WITH_PARENT);
  }

  private static InstrumentedJvm sendingARequest() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-D" + TestApplication.SEND_REQUEST + "=true",
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.service.name=smoke-test");
  }
}
