/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Starts a real JVM with the built agent attached.
 *
 * <p>The packaging tests prove the artifact has the right shape. This proves the JVM accepts it:
 * that the manifest entry points resolve, the agent's premain runs, and an application that knows
 * nothing about OpenTelemetry still reaches the end of {@code main}.
 *
 * <p>TODO: assert on exported telemetry, not just startup. That needs an OTLP receiver in the test
 * and protobuf decoding of the export request, which is the natural next increment once the
 * configuration surface is decided.
 */
class AgentAttachTest {

  @Test
  void attachesToAJvmAndLetsTheApplicationComplete() throws IOException, InterruptedException {
    ProcessOutput result = runTestApplicationWithAgent();

    assertThat(result.exitCode).as("JVM exit code, output was:%n%s", result.output).isZero();
    assertThat(result.output).contains("test-application-completed");
  }

  @Test
  void reportsTheDash0ImplementationVersionAtStartup() throws IOException, InterruptedException {
    ProcessOutput result = runTestApplicationWithAgent();

    // The agent's version logger reports the Implementation-Version from the jar
    // manifest. Seeing the "dash0-" prefix proves both that the agent actually
    // started and that our manifest, rather than upstream's, is the one in force.
    assertThat(result.output).as("agent startup output was:%n%s", result.output).contains("dash0-");
  }

  private static ProcessOutput runTestApplicationWithAgent()
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("dash0.agent.jar"));
    command.add("-Dotel.javaagent.debug=true");
    // Nothing should try to reach a collector during a packaging smoke test.
    command.add("-Dotel.traces.exporter=none");
    command.add("-Dotel.metrics.exporter=none");
    command.add("-Dotel.logs.exporter=none");
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] output = readAllBytes(process);
    if (!process.waitFor(2, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("the instrumented JVM did not exit within two minutes");
    }
    return new ProcessOutput(process.exitValue(), new String(output, StandardCharsets.UTF_8));
  }

  private static byte[] readAllBytes(Process process) throws IOException {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int read;
    while ((read = process.getInputStream().read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private static final class ProcessOutput {
    final int exitCode;
    final String output;

    ProcessOutput(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }
  }
}
