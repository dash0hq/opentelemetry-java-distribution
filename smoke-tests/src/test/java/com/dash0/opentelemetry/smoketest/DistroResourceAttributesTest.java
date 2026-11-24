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
 */
class DistroResourceAttributesTest {

  @Test
  void exportsTheDash0DistroNameRatherThanUpstreams() throws Exception {
    String exported = exportingTheResourceToTheLog("-Dotel.service.name=smoke-test");

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-java-instrumentation\"");
  }

  @Test
  void exportsTheDistributionVersionRatherThanTheAgentManifestVersion() throws Exception {
    String exported = exportingTheResourceToTheLog("-Dotel.service.name=smoke-test");

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains(
            "telemetry.distro.version=\"" + System.getProperty("dash0.distro.version") + "\"");
  }

  @Test
  void keepsTheDistroAttributesWhileTheSpringBootNameDetectorAlsoContributes() throws Exception {
    String exported = exportingTheResourceToTheLog("-Dspring.application.name=my-spring-app");

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("service.name=\"my-spring-app\"")
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-java-instrumentation\"");
  }

  private static String exportingTheResourceToTheLog(String... extraJvmArguments) throws Exception {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.metrics.exporter=logging",
            "-Dotel.metric.export.interval=500")
        .withJvmArguments(extraJvmArguments)
        .exitingAfter(4000)
        .run();
  }
}
