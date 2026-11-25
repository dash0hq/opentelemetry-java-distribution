/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Asserts that a real instrumented JVM configures itself from {@value #DEFAULT_CONFIGURATION_FILE}
 * when {@code OTEL_CONFIG_FILE} is unset. See {@code docs/configuration.md}.
 *
 * <p>Every test that needs the file installs it and removes it again, and skips itself where the
 * directory is not writable. CI makes it writable for the runner user.
 */
class DefaultConfigurationFileTest {

  static final String DEFAULT_CONFIGURATION_FILE = "/etc/opentelemetry/java/otel-config.yaml";

  private static final Path DEFAULT_CONFIGURATION_PATH = Paths.get(DEFAULT_CONFIGURATION_FILE);

  @Test
  void theAgentConfiguresItselfFromTheDefaultFileWhenOtelConfigFileIsUnset() throws Exception {
    String exported = withTheDefaultConfigurationFile(InstrumentedJvm.runningTheTestApplication());

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .contains("service.name=\"default-config-file-smoke-test\"");
  }

  @Test
  void theDash0AttributesAndDetectorsReachTheDefaultFileToo() throws Exception {
    String exported = withTheDefaultConfigurationFile(InstrumentedJvm.runningTheTestApplication());

    assertThat(exported)
        .as(
            "DeclarativeConfigUtil.create runs every DeclarativeConfigurationCustomizerProvider"
                + " over a model this SPI supplied, exactly as it does over a model parsed from"
                + " the file OTEL_CONFIG_FILE names. The JVM's output was:%n%s",
            exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("host.name=")
        .contains("process.pid=");
  }

  @Test
  void dash0DisableStillSwitchesTheSdkOffUnderTheDefaultFile() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .withEnvironmentVariable("DASH0_DISABLE", "true"));

    assertThat(exported)
        .as(
            "a kill switch that the shipped file cancels is not one, and the file is now on every"
                + " host with the Dash0 Java package installed. The application still ran to"
                + " completion, which InstrumentedJvm.run checks, so this is a silenced SDK and"
                + " not a dead agent. The JVM's output was:%n%s",
            exported)
        .doesNotContain("service.name=\"default-config-file-smoke-test\"");
  }

  @Test
  void anExplicitOtelConfigFileBeatsTheDefaultFile() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .withEnvironmentVariable("OTEL_CONFIG_FILE", fixture("/declarative-config.yaml")));

    assertThat(exported)
        .as(
            "the SDK consults the DeclarativeConfigurationProvider SPI before it reads"
                + " otel.config.file, so a provider that answered unconditionally would invert"
                + " this. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"")
        .doesNotContain("default-config-file-smoke-test");
  }

  @Test
  void theOtelConfigFileSystemPropertyBeatsTheDefaultFileToo() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .withJvmArguments("-Dotel.config.file=" + fixture("/declarative-config.yaml")));

    assertThat(exported)
        .as(
            "the provider reads the system property as well as the environment variable, which is"
                + " the pair DefaultConfigProperties would have given maybeConfigureFromFile. The"
                + " JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"")
        .doesNotContain("default-config-file-smoke-test");
  }

  @Test
  void otelSdkDisabledStillSwitchesTheSdkOffUnderTheDefaultFile() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .withEnvironmentVariable("OTEL_SDK_DISABLED", "true"));

    assertThat(exported)
        .as(
            "a declaratively configured SDK reads no OTEL_SDK_DISABLED, so the provider stands"
                + " aside and the environment path honours it. A host-wide default file that"
                + " loaded anyway would restart a workload an operator silenced. The application"
                + " still ran to completion, which InstrumentedJvm.run checks, so this is a"
                + " silenced SDK and not a dead agent. The JVM's output was:%n%s",
            exported)
        .doesNotContain("service.name=\"default-config-file-smoke-test\"");
  }

  @Test
  void theEnvironmentConfiguresTheSdkWhenTheDefaultFileIsAbsent() throws Exception {
    assumeFalse(
        Files.exists(DEFAULT_CONFIGURATION_PATH),
        DEFAULT_CONFIGURATION_FILE + " exists on this host");

    String exported =
        InstrumentedJvm.runningTheTestApplication()
            .withJvmArguments(
                "-Dotel.traces.exporter=none",
                "-Dotel.logs.exporter=none",
                "-Dotel.metrics.exporter=logging",
                "-Dotel.metric.export.interval=500",
                "-Dotel.service.name=environment-smoke-test")
            .exitingAfter(4000)
            .run();

    assertThat(exported)
        .as(
            "with neither OTEL_CONFIG_FILE nor the default file, the distribution behaves as it"
                + " did before the default file existed. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"environment-smoke-test\"")
        .contains("telemetry.distro.name=\"dash0-java\"");
  }

  @Test
  void theExtensionJarUsesTheDefaultFileToo() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .attaching(Paths.get(System.getProperty("dash0.upstream.agent.jar")))
                .withJvmArguments(
                    "-Dotel.javaagent.extensions=" + System.getProperty("dash0.extension.jar")));

    assertThat(exported)
        .as(
            "that the component loader reaches the extension class loader before otel.config.file"
                + " is read is a separate question from the agent jar, so both delivery shapes are"
                + " exercised. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"default-config-file-smoke-test\"")
        .contains("telemetry.distro.name=\"dash0-java\"");
  }

  @Test
  void anExplicitOtelConfigFileBeatsTheDefaultFileThroughTheExtensionJarToo() throws Exception {
    String exported =
        withTheDefaultConfigurationFile(
            InstrumentedJvm.runningTheTestApplication()
                .attaching(Paths.get(System.getProperty("dash0.upstream.agent.jar")))
                .withJvmArguments(
                    "-Dotel.javaagent.extensions=" + System.getProperty("dash0.extension.jar"))
                .withEnvironmentVariable("OTEL_CONFIG_FILE", fixture("/declarative-config.yaml")));

    assertThat(exported)
        .as(
            "the extension jar reaches the same provider through a different class loader, so the"
                + " precedence is asserted in both delivery shapes. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"")
        .doesNotContain("default-config-file-smoke-test");
  }

  /**
   * Runs {@code jvm} with the smoke-test fixture installed at {@value #DEFAULT_CONFIGURATION_FILE},
   * and removes the file again, or skips the test where this host will not have it written.
   */
  private static String withTheDefaultConfigurationFile(InstrumentedJvm jvm) throws Exception {
    assumeFalse(
        Files.exists(DEFAULT_CONFIGURATION_PATH),
        DEFAULT_CONFIGURATION_FILE + " already exists on this host");
    try {
      Files.createDirectories(DEFAULT_CONFIGURATION_PATH.getParent());
      Files.copy(Paths.get(fixture("/default-config-file.yaml")), DEFAULT_CONFIGURATION_PATH);
    } catch (IOException e) {
      abort(DEFAULT_CONFIGURATION_FILE + " cannot be written on this host: " + e);
    }
    try {
      return jvm.exitingAfter(4000).run();
    } finally {
      Files.delete(DEFAULT_CONFIGURATION_PATH);
    }
  }

  private static String fixture(String name) throws URISyntaxException {
    return Paths.get(DefaultConfigurationFileTest.class.getResource(name).toURI()).toString();
  }
}
