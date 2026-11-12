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
 * <p>Six test classes launch a JVM and each used to carry its own copy of this code. The copies
 * drifted: one checked the child's exit code and five did not, so a test could assert that
 * telemetry was absent and pass against a JVM that had died before producing any.
 *
 * <p>{@link #run()} therefore refuses to return output from a run that did not complete. Every
 * assertion a caller then makes is about a JVM that reached the end of {@code main}, which is what
 * makes an assertion phrased as an absence mean anything at all.
 *
 * <p>The child's output is redirected to a file rather than read from a pipe. Reading the pipe to
 * end of file first and calling {@code waitFor} afterwards puts the timeout out of reach, because
 * end of file arrives when the child exits: a child that hangs blocks the read forever and the
 * timeout is never consulted. The agent is attached with {@code -javaagent:}, so a deadlock in its
 * premain is a realistic way for that to happen, and the cost is a CI job that runs until GitHub
 * kills it six hours later.
 */
final class InstrumentedJvm {

  private static final int TIMEOUT_MINUTES = 2;

  private String agentJar = System.getProperty("dash0.agent.jar");
  private final List<String> jvmArguments = new ArrayList<>();
  private final Map<String, String> environment = new LinkedHashMap<>();
  private Integer millisecondsBeforeExit;
  private Path executableJar;
  private Path workingDirectory;

  private InstrumentedJvm() {}

  static InstrumentedJvm runningTheTestApplication() {
    return new InstrumentedJvm();
  }

  /** Attaches {@code agent} instead of the agent jar this build produced. */
  InstrumentedJvm attaching(Path agent) {
    agentJar = agent.toString();
    return this;
  }

  /**
   * Launches {@code jar} with {@code -jar} instead of launching the class path with {@code -cp}.
   */
  InstrumentedJvm launchedFromTheExecutableJar(Path jar) {
    executableJar = jar;
    return this;
  }

  /** Starts the JVM in {@code directory} instead of in the directory Gradle runs the tests in. */
  InstrumentedJvm inWorkingDirectory(Path directory) {
    workingDirectory = directory;
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
    if (executableJar == null) {
      command.add("-cp");
      command.add(System.getProperty("dash0.test.app.classes"));
      command.add(TestApplication.class.getName());
    } else {
      command.add("-jar");
      command.add(executableJar.toString());
    }
    if (millisecondsBeforeExit != null) {
      command.add(String.valueOf(millisecondsBeforeExit));
    }

    Path outputFile = Files.createTempFile("dash0-smoke-test", ".log");
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile());
      if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile());
      }
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

  /**
   * Whether {@code name} is a variable the SDK, one of its resource detectors, or this distribution
   * reads.
   *
   * <p>{@link ProcessBuilder#environment()} starts from the environment Gradle was launched with,
   * so without this the child inherits whatever the developer happens to export. A shell carrying
   * {@code OTEL_EXPORTER_OTLP_ENDPOINT} fails the two tests that assume no endpoint is configured,
   * and one carrying {@code DASH0_DISABLE} or {@code OTEL_SERVICE_NAME} changes what several others
   * read back. Exporting these is ordinary for anyone working on OpenTelemetry, and the result is a
   * red build on a correct tree with nothing to explain it.
   *
   * <p>Removal is selective rather than by clearing the map, which would also drop {@code PATH} and
   * {@code JAVA_HOME} and leave nothing able to start.
   */
  private static boolean configuresTheSdk(String name) {
    return name.startsWith("OTEL_")
        || name.startsWith("DASH0_")
        || name.equals("SPRING_APPLICATION_NAME");
  }

  private static String contentOf(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }
}
