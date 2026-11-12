/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asserts which value a real instrumented JVM reports as {@code service.name} for each way of
 * launching an application.
 */
class ServiceNameDetectionTest {

  @Test
  void reportsUnknownServiceWhenNothingNamesTheApplication(@TempDir Path workingDirectory)
      throws Exception {
    String exported = exportingTheResourceToTheLog().inWorkingDirectory(workingDirectory).run();

    assertServiceName(exported, "unknown_service:java");
  }

  @Test
  void takesTheNameFromTheSpringApplicationNameSystemProperty(@TempDir Path workingDirectory)
      throws Exception {
    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .withJvmArguments("-Dspring.application.name=my-spring-app")
            .run();

    assertServiceName(exported, "my-spring-app");
  }

  @Test
  void takesTheNameFromTheSpringApplicationNameEnvironmentVariable(@TempDir Path workingDirectory)
      throws Exception {
    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .withEnvironmentVariable("SPRING_APPLICATION_NAME", "env-spring-app")
            .run();

    assertServiceName(exported, "env-spring-app");
  }

  @Test
  void takesTheNameFromAnApplicationPropertiesFileInTheWorkingDirectory(
      @TempDir Path workingDirectory) throws Exception {
    Files.write(
        workingDirectory.resolve("application.properties"),
        "spring.application.name=from-properties-file".getBytes(StandardCharsets.UTF_8));

    String exported = exportingTheResourceToTheLog().inWorkingDirectory(workingDirectory).run();

    assertServiceName(exported, "from-properties-file");
  }

  @Test
  void takesTheNameFromTheExecutableJarFileName(@TempDir Path workingDirectory) throws Exception {
    Path jar = executableJar(workingDirectory, "my-payments-service.jar", null);

    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .launchedFromTheExecutableJar(jar)
            .run();

    assertServiceName(exported, "my-payments-service");
  }

  @Test
  void prefersTheManifestImplementationTitleOverTheExecutableJarFileName(
      @TempDir Path workingDirectory) throws Exception {
    Path jar = executableJar(workingDirectory, "my-payments-service.jar", "titled-from-manifest");

    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .launchedFromTheExecutableJar(jar)
            .run();

    assertServiceName(exported, "titled-from-manifest");
  }

  @Test
  void prefersTheSpringApplicationNameOverTheExecutableJarFileName(@TempDir Path workingDirectory)
      throws Exception {
    Path jar = executableJar(workingDirectory, "my-payments-service.jar", null);

    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .withJvmArguments("-Dspring.application.name=my-spring-app")
            .launchedFromTheExecutableJar(jar)
            .run();

    assertServiceName(exported, "my-spring-app");
  }

  @Test
  void prefersOtelServiceNameOverEveryDetector(@TempDir Path workingDirectory) throws Exception {
    Path jar = executableJar(workingDirectory, "my-payments-service.jar", "titled-from-manifest");

    String exported =
        exportingTheResourceToTheLog()
            .inWorkingDirectory(workingDirectory)
            .withJvmArguments("-Dspring.application.name=my-spring-app")
            .withEnvironmentVariable("OTEL_SERVICE_NAME", "from-otel-service-name")
            .launchedFromTheExecutableJar(jar)
            .run();

    assertServiceName(exported, "from-otel-service-name");
  }

  private static void assertServiceName(String exported, String expected) {
    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("service.name=\"" + expected + "\"");
  }

  private static InstrumentedJvm exportingTheResourceToTheLog() {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.metrics.exporter=logging",
            "-Dotel.metric.export.interval=500")
        .exitingAfter(4000);
  }

  /**
   * Writes an executable jar named {@code fileName} into {@code directory}, holding {@link
   * TestApplication} and, when {@code implementationTitle} is not null, that {@code
   * Implementation-Title} manifest attribute.
   *
   * @return the path of the jar that was written
   */
  private static Path executableJar(Path directory, String fileName, String implementationTitle)
      throws IOException {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.put(Attributes.Name.MAIN_CLASS, TestApplication.class.getName());
    if (implementationTitle != null) {
      attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, implementationTitle);
    }

    Path jar = directory.resolve(fileName);
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      for (String classesDirectory :
          System.getProperty("dash0.test.app.classes").split(File.pathSeparator)) {
        Path root = Paths.get(classesDirectory);
        if (!Files.isDirectory(root)) {
          continue;
        }
        List<Path> classFiles;
        try (Stream<Path> entries = Files.walk(root)) {
          classFiles = entries.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path classFile : classFiles) {
          out.putNextEntry(
              new JarEntry(root.relativize(classFile).toString().replace(File.separatorChar, '/')));
          Files.copy(classFile, out);
          out.closeEntry();
        }
      }
    }
    return jar;
  }
}
