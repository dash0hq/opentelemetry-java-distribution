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
import org.junit.jupiter.api.Test;

/**
 * Checks the standalone extension jar, the second delivery shape.
 *
 * <p>The extension is loaded by the unmodified upstream agent via {@code
 * OTEL_JAVAAGENT_EXTENSIONS}, so its layout is the opposite of the repackaged agent's: ordinary
 * {@code .class} files at the jar root, not {@code .classdata} under {@code inst/}.
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
  void registersTheResourceProviderService() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR))
        .contains("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider");
  }

  @Test
  void doesNotUseTheIsolatedAgentLayout() throws IOException {
    // inst/ and .classdata belong to the repackaged agent. If they appear here
    // the extension was built with the wrong convention plugin and the agent
    // will load nothing from it.
    assertThat(entryNamesOf(EXTENSION_JAR))
        .noneMatch(name -> name.startsWith("inst/"))
        .noneMatch(name -> name.endsWith(".classdata"));
  }

  @Test
  void carriesTheSameGeneratedVersionResourceAsTheAgent() throws IOException {
    assertThat(entryNamesOf(EXTENSION_JAR)).contains("dash0-distro-version.properties");
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
