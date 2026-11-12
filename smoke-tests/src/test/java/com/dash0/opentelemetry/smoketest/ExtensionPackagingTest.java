/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/**
 * Checks the standalone extension jar, which holds ordinary {@code .class} files at the jar root
 * rather than {@code .classdata} under {@code inst/}.
 */
class ExtensionPackagingTest {

  private static final Path EXTENSION_JAR = Paths.get(System.getProperty("dash0.extension.jar"));

  @Test
  void bundlesTheDistroClassesAsOrdinaryClassFiles() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR))
        .contains("com/dash0/opentelemetry/DistroResource.class")
        .contains("com/dash0/opentelemetry/DistroResourceProvider.class");
  }

  @Test
  void registersBothServicesTheDistributionImplements() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR))
        .contains("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider")
        .contains(
            "META-INF/services/"
                + "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider");
  }

  @Test
  void carriesTheLicence() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR)).contains("META-INF/LICENSE");
  }

  @Test
  void doesNotUseTheIsolatedAgentLayout() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR))
        .noneMatch(name -> name.startsWith("inst/"))
        .noneMatch(name -> name.endsWith(".classdata"));
  }

  @Test
  void carriesTheSameGeneratedVersionResourceAsTheAgent() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR)).contains("dash0-distro-version.properties");
  }

  @Test
  void declaresItsIdentityInTheManifest() throws IOException {
    try (JarFile jar = new JarFile(EXTENSION_JAR.toFile())) {
      Manifest manifest = jar.getManifest();

      assertThat(manifest.getMainAttributes().getValue("Implementation-Vendor"))
          .isEqualTo("Dash0 Inc.");
      assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
          .isEqualTo("dash0-opentelemetry-agentextension");
      assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
          .as("a published release asset has to be able to report its own version")
          .startsWith("dash0-")
          .contains("-otel-");
    }
  }

  private static List<String> entryNamesOf(Path jarPath) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      List<String> names = new ArrayList<>();
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        names.add(entries.nextElement().getName());
      }
      return names;
    }
  }
}
