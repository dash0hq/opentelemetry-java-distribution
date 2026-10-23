/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    String exported = runWithDeclarativeConfiguration(Collections.emptyMap());

    assertThat(exported)
        .as("the JVM's output was:%n%s", exported)
        .doesNotContain("OpenTelemetry Javaagent failed to start")
        .contains("service.name=\"declarative-smoke-test\"");
  }

  @Test
  void theDash0ResourceProvidersDoNotRun() throws Exception {
    String exported = runWithDeclarativeConfiguration(Collections.emptyMap());

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
    String exported = runWithDeclarativeConfiguration(Collections.emptyMap());

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
        runWithDeclarativeConfiguration(Collections.singletonMap("DASH0_DISABLE", "true"));

    assertThat(exported)
        .as(
            "no AutoConfigurationCustomizerProvider runs, so the setting that would switch the SDK"
                + " off never reaches it and telemetry keeps flowing. The JVM's output was:%n%s",
            exported)
        .contains("service.name=\"declarative-smoke-test\"");
  }

  private static String runWithDeclarativeConfiguration(Map<String, String> environment)
      throws IOException, InterruptedException, URISyntaxException {
    Path configFile =
        Paths.get(
            DeclarativeConfigurationTest.class.getResource("/declarative-config.yaml").toURI());

    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("dash0.agent.jar"));
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());
    command.add("3000");

    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("OTEL_CONFIG_FILE", configFile.toString());
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
