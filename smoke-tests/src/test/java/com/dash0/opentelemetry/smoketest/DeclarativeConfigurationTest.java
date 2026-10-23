/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Records what this distribution does when {@code OTEL_CONFIG_FILE} is set.
 *
 * <p>These assertions describe behaviour the distribution does not want. Declarative configuration
 * replaces environment configuration wholesale and consults a different pair of SPIs, so none of
 * the Dash0 customisations run: not the resource providers, not the configuration customizer.
 * {@code docs/configuration.md} explains why and what supporting it would take.
 *
 * <p>The test exists so the day that changes is a test failure rather than a discovery. Upstream is
 * actively moving on declarative configuration, and the next agent bump could start honouring these
 * SPIs. Without this, the distribution would keep claiming no support for something that had
 * quietly started working, and the documented measurement would rot.
 *
 * <p>A failure here is therefore not necessarily a defect. Read it as a prompt to re-measure,
 * update {@code docs/configuration.md}, and decide whether declarative support can now be declared.
 */
class DeclarativeConfigurationTest {

  @Test
  void theAgentStartsAndExportsWhatTheFileAsksFor() throws Exception {
    String exported = configuredFromTheYamlFile().run();

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .contains("service.name=\"declarative-smoke-test\"");
  }

  @Test
  void theDash0ResourceProvidersDoNotRun() throws Exception {
    String exported = configuredFromTheYamlFile().run();

    assertThat(exported)
        .as(
            "DistroResourceProvider is a ResourceProvider, which declarative configuration does"
                + " not consult. Upstream's own value survives because it registers a"
                + " DeclarativeConfigurationCustomizerProvider instead. The JVM's output was:%n%s",
            exported)
        .contains("telemetry.distro.name=\"opentelemetry-javaagent\"")
        .doesNotContain("telemetry.distro.name=\"dash0-java\"");
  }

  @Test
  void theUpstreamHostAndProcessDetectorsDoNotRunEither() throws Exception {
    String exported = configuredFromTheYamlFile().run();

    assertThat(exported)
        .as(
            "every ResourceProvider is skipped, not only the Dash0 ones, so the resource is far"
                + " smaller than under environment configuration. The JVM's output was:%n%s",
            exported)
        .doesNotContain("host.name=")
        .doesNotContain("process.pid=");
  }

  @Test
  void dash0DisableIsIgnored() throws Exception {
    String exported =
        configuredFromTheYamlFile().withEnvironmentVariable("DASH0_DISABLE", "true").run();

    assertThat(exported)
        .as(
            "no AutoConfigurationCustomizerProvider runs, so the setting that would switch the SDK"
                + " off never reaches it and telemetry keeps flowing. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"");
  }

  private static InstrumentedJvm configuredFromTheYamlFile() throws Exception {
    return InstrumentedJvm.runningTheTestApplication()
        .withEnvironmentVariable(
            "OTEL_CONFIG_FILE",
            Paths.get(
                    DeclarativeConfigurationTest.class
                        .getResource("/declarative-config.yaml")
                        .toURI())
                .toString())
        .exitingAfter(3000);
  }
}
