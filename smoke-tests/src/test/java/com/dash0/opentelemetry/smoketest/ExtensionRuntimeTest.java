/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the Dash0 code as an extension on the stock upstream agent.
 *
 * <p>{@code ExtensionPackagingTest} reads entry names out of the extension jar. That shows the jar
 * has the right shape and nothing more. Until this test existed, no test attached the extension to
 * a JVM, even though it is published as a release asset and the README calls it a supported
 * artifact.
 *
 * <p>The two delivery shapes are not interchangeable internally. The agent jar relocates its
 * packages and loads the Dash0 classes from {@code inst/} as {@code .classdata} entries; the
 * extension is relocated not at all and is loaded as ordinary class files. A defect confined to
 * either one is invisible from the other, which is the reason both are exercised here.
 */
class ExtensionRuntimeTest {

  @TempDir Path directory;

  @Test
  void contributesTheDash0DistroAttributes() throws Exception {
    String exported = upstreamAgentWith(extensionJar(), "logging").run();

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("telemetry.distro.name=\"opentelemetry-java-instrumentation\"");
  }

  @Test
  void honoursDash0Disable() throws Exception {
    String exported =
        upstreamAgentWith(extensionJar(), "logging")
            .withEnvironmentVariable("DASH0_DISABLE", "true")
            .run();

    assertThat(exported).as("the JVM's output was:%n%s", exported).doesNotContain("metric:");
  }

  @Test
  void honoursTheCollectorBaseUrl() throws Exception {
    String exported =
        upstreamAgentWith(extensionJar(), "otlp")
            .withEnvironmentVariable(
                "DASH0_OTEL_COLLECTOR_BASE_URL", "http://extension-endpoint.invalid:4318")
            .run();

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .contains("extension-endpoint.invalid");
  }

  @Test
  void letsTheStandardEndpointBeatTheCollectorBaseUrl() throws Exception {
    Map<String, String> environment = new HashMap<>();
    environment.put("DASH0_OTEL_COLLECTOR_BASE_URL", "http://extension-loses.invalid:4318");
    environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://extension-wins.invalid:4318");

    String exported = upstreamAgentWith(extensionJar(), "otlp").withEnvironment(environment).run();

    assertThat(exported)
        .as(
            "the precedence has to be the same in both delivery shapes. The output was:%n%s",
            exported)
        .contains("extension-wins.invalid")
        .doesNotContain("extension-loses.invalid");
  }

  @Test
  void contributesNoPackageAttributesAndStartsAnyway() throws Exception {
    Path extension = extensionJar();
    Files.write(
        extension.resolveSibling("dash0-resource-attributes.properties"),
        "dash0.package.name=beside-the-extension\n".getBytes(StandardCharsets.UTF_8));

    String exported = upstreamAgentWith(extension, "logging").run();

    assertThat(exported)
        .as(
            "Loaded from the extension jar, Dash0PackageResourceProvider reports an otel: code"
                + " source rather than a file, so it can resolve no directory. The requirement is"
                + " that it contributes nothing and lets the agent start. The JVM's output"
                + " was:%n%s",
            exported)
        .doesNotContain("dash0.package.")
        .contains("telemetry.distro.name=\"dash0-java\"");
  }

  private Path extensionJar() throws IOException {
    Path copy = directory.resolve("dash0-opentelemetry-agentextension.jar");
    Files.copy(
        Paths.get(System.getProperty("dash0.extension.jar")),
        copy,
        StandardCopyOption.REPLACE_EXISTING);
    return copy;
  }

  private static InstrumentedJvm upstreamAgentWith(Path extension, String metricsExporter) {
    return InstrumentedJvm.runningTheTestApplication()
        .attaching(Paths.get(System.getProperty("dash0.upstream.agent.jar")))
        .withJvmArguments(
            "-Dotel.javaagent.extensions=" + extension,
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.metrics.exporter=" + metricsExporter,
            "-Dotel.metric.export.interval=500",
            "-Dotel.service.name=smoke-test")
        .exitingAfter(4000);
  }
}
