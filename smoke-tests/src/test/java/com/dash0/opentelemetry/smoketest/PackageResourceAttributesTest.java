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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asserts that a properties file written beside the agent jar reaches the exported resource.
 *
 * <p>Each case copies the agent jar into a fresh directory, since the provider reads the directory
 * the jar it is loaded from sits in.
 */
class PackageResourceAttributesTest {

  @TempDir Path installDirectory;

  @Test
  void exportsTheAttributesWrittenBesideTheAgentJar() throws Exception {
    Path agentJar = copyAgentJar();
    Files.write(
        agentJar.resolveSibling("dash0-resource-attributes.properties"),
        ("dash0.package.name=dash0-java-autoinstrumentation\ndash0.package.version=1.2.3\n")
            .getBytes(StandardCharsets.UTF_8));

    String exported = exportingTheResourceToTheLog(agentJar);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("dash0.package.name=\"dash0-java-autoinstrumentation\"")
        .contains("dash0.package.version=\"1.2.3\"");
  }

  @Test
  void exportsNoPackageAttributesWithoutThatFile() throws Exception {
    String exported = exportingTheResourceToTheLog(copyAgentJar());

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("dash0.package.");
  }

  @Test
  void losesEveryKeyAnUpstreamDetectorAlsoSets() throws Exception {
    Path agentJar = copyAgentJar();
    Files.write(
        agentJar.resolveSibling("dash0-resource-attributes.properties"),
        ("dash0.package.name=dash0-java-autoinstrumentation\nhost.name=from-the-package-file\n")
            .getBytes(StandardCharsets.UTF_8));

    String exported = exportingTheResourceToTheLog(agentJar);

    assertThat(exported)
        .as(
            "Dash0PackageResourceProvider declares Integer.MIN_VALUE so that every detector is"
                + " applied after it and overwrites what the package declared. Dropping that order"
                + " leaves it tied with the detectors on 0, and the winner is then decided by"
                + " position in the merged META-INF/services file, which no other test asserts."
                + " Exported resource attributes were:%n%s",
            exported)
        .contains("dash0.package.name=\"dash0-java-autoinstrumentation\"")
        .contains("host.name=")
        .doesNotContain("from-the-package-file");
  }

  private Path copyAgentJar() throws IOException {
    Path agentJar = installDirectory.resolve("opentelemetry-javaagent.jar");
    Files.copy(
        Paths.get(System.getProperty("dash0.agent.jar")),
        agentJar,
        StandardCopyOption.REPLACE_EXISTING);
    return agentJar;
  }

  private static String exportingTheResourceToTheLog(Path agentJar) throws Exception {
    return InstrumentedJvm.runningTheTestApplication()
        .attaching(agentJar)
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.metrics.exporter=logging",
            "-Dotel.metric.export.interval=500",
            "-Dotel.service.name=smoke-test")
        .exitingAfter(4000)
        .run();
  }
}
