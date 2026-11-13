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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link TestApplication} in a real JVM with an agent attached, and returns what it printed.
 *
 * <p>{@link #run()} refuses to return output from a run that did not complete.
 */
final class InstrumentedJvm {

  private static final int TIMEOUT_MINUTES = 2;

  private String agentJar = System.getProperty("dash0.agent.jar");
  private final List<String> jvmArguments = new ArrayList<>();
  private final Map<String, String> environment = new LinkedHashMap<>();
  private Integer millisecondsBeforeExit;

  private InstrumentedJvm() {}

  static InstrumentedJvm runningTheTestApplication() {
    return new InstrumentedJvm();
  }

  /** Attaches {@code agent} instead of the agent jar this build produced. */
  InstrumentedJvm attaching(Path agent) {
    agentJar = agent.toString();
    return this;
  }

  InstrumentedJvm withJvmArguments(String... arguments) {
    jvmArguments.addAll(Arrays.asList(arguments));
    return this;
  }

  InstrumentedJvm withEnvironmentVariable(String name, String value) {
    environment.put(name, value);
    return this;
  }

  InstrumentedJvm withEnvironment(Map<String, String> variables) {
    environment.putAll(variables);
    return this;
  }

  /** Keeps the application alive this long, so that a periodic exporter runs at least once. */
  InstrumentedJvm exitingAfter(int milliseconds) {
    millisecondsBeforeExit = milliseconds;
    return this;
  }

  /**
   * Starts the JVM, waits for it, and returns its combined standard output and standard error.
   *
   * @throws AssertionError if the JVM did not exit within two minutes, if the agent reported that
   *     it failed to start, if the exit code was not zero, or if the application did not reach the
   *     end of {@code main}
   */
  String run() throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + agentJar);
    command.addAll(jvmArguments);
    command.add("-cp");
    command.add(System.getProperty("dash0.test.app.classes"));
    command.add(TestApplication.class.getName());
    if (millisecondsBeforeExit != null) {
      command.add(String.valueOf(millisecondsBeforeExit));
    }

    Path outputFile = Files.createTempFile("dash0-smoke-test", ".log");
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile());
      builder.environment().keySet().removeIf(InstrumentedJvm::configuresTheSdk);
      builder.environment().putAll(environment);

      Process process = builder.start();
      if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new AssertionError(
            "the instrumented JVM did not exit within "
                + TIMEOUT_MINUTES
                + " minutes; it had printed:\n"
                + contentOf(outputFile));
      }
      String output = contentOf(outputFile);
      assertThat(output)
          .as("the agent did not start, so nothing below this can mean anything:%n%s", output)
          .doesNotContain("OpenTelemetry Javaagent failed to start");
      assertThat(process.exitValue())
          .as("the instrumented JVM exited non-zero, so its output was:%n%s", output)
          .isZero();
      assertThat(output)
          .as("the application never reached the end of main, so its output was:%n%s", output)
          .contains(TestApplication.COMPLETION_MARKER);
      return output;
    } finally {
      Files.deleteIfExists(outputFile);
    }
  }

  /** Whether {@code name} is a variable the SDK or this distribution reads. */
  private static boolean configuresTheSdk(String name) {
    return name.startsWith("OTEL_") || name.startsWith("DASH0_");
  }

  private static String contentOf(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }
}
