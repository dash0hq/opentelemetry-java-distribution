/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asserts that a properties file written beside the agent jar reaches the exported resource.
 *
 * <p>The unit tests for {@code Dash0PackageResourceProvider} supply the path directly, so they
 * cannot show that the provider finds the file on its own. That resolution goes through the code
 * source of a class the agent loads from {@code inst/} as a {@code .classdata} entry rather than as
 * an ordinary class file, which is unusual enough that it has to be demonstrated rather than
 * assumed. When it breaks, the agent starts normally and the package attributes are simply absent.
 *
 * <p>Each case copies the agent jar into a fresh directory, because the provider reads the
 * directory the jar it is loaded from sits in, and the build output directory is shared with every
 * other test.
 */
class PackageResourceAttributesTest {

  @TempDir Path installDirectory;

  @Test
  void exportsTheAttributesWrittenBesideTheAgentJar() throws IOException, InterruptedException {
    Path agentJar = copyAgentJar();
    Files.write(
        agentJar.resolveSibling("dash0-resource-attributes.properties"),
        ("dash0.package.name=dash0-java-autoinstrumentation\ndash0.package.version=1.2.3\n")
            .getBytes(StandardCharsets.UTF_8));

    String exported = runInstrumentedApplication(agentJar);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("dash0.package.name=\"dash0-java-autoinstrumentation\"")
        .contains("dash0.package.version=\"1.2.3\"");
  }

  @Test
  void exportsNoPackageAttributesWithoutThatFile() throws IOException, InterruptedException {
    String exported = runInstrumentedApplication(copyAgentJar());

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("dash0.package.");
  }

  private Path copyAgentJar() throws IOException {
    Path agentJar = installDirectory.resolve("opentelemetry-javaagent.jar");
    Files.copy(
        Paths.get(System.getProperty("dash0.agent.jar")),
        agentJar,
        StandardCopyOption.REPLACE_EXISTING);
    return agentJar;
  }

  private static String runInstrumentedApplication(Path agentJar)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + agentJar);
    command.add("-Dotel.traces.exporter=none");
    command.add("-Dotel.logs.exporter=none");
    command.add("-Dotel.metrics.exporter=logging");
    command.add("-Dotel.metric.export.interval=500");
    command.add("-Dotel.service.name=smoke-test");
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());
    command.add("4000");

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
    String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    assertThat(output)
        .as("the agent did not start, so nothing below this can mean anything:%n%s", output)
        .doesNotContain("OpenTelemetry Javaagent failed to start");
    return output;
  }
}
