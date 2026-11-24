/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/** Checks the shape of the assembled agent jar. */
class AgentPackagingTest {

  private static final Path AGENT_JAR = Paths.get(System.getProperty("dash0.agent.jar"));

  private static final Path UPSTREAM_AGENT_JAR =
      Paths.get(System.getProperty("dash0.upstream.agent.jar"));

  private static final String RESOURCE_PROVIDER_SERVICE_FILE =
      "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider";

  @Test
  void bundlesTheDistroClassesUnderInstAsClassdata() throws IOException {
    List<String> entries = entryNamesOf(AGENT_JAR);

    assertThat(entries)
        .contains("inst/com/dash0/opentelemetry/DistroResource.classdata")
        .contains("inst/com/dash0/opentelemetry/DistroResourceProvider.classdata");
  }

  @Test
  void neverShipsDistroClassesAsPlainClassFiles() throws IOException {
    assertThat(entryNamesOf(AGENT_JAR))
        .noneMatch(name -> name.startsWith("inst/com/dash0/") && name.endsWith(".class"));
  }

  @Test
  void registersBothDash0ProvidersInTheMergedServiceFile() throws IOException {
    assertThat(providersRegisteredIn(AGENT_JAR))
        .as("the Dash0 providers must survive the service-file merge with upstream's own")
        .contains(
            "com.dash0.opentelemetry.DistroResourceProvider",
            "com.dash0.opentelemetry.Dash0PackageResourceProvider");
  }

  @Test
  void keepsEveryUpstreamResourceProviderAlongsideTheDash0Ones() throws IOException {
    assertThat(providersRegisteredIn(AGENT_JAR))
        .as(
            "every provider upstream registers has to survive the merge. Comparing against"
                + " upstream's own copy keeps the expected set correct when upstream adds or"
                + " removes a detector, which a count written down here would not.")
        .containsAll(providersRegisteredIn(UPSTREAM_AGENT_JAR));
  }

  private static List<String> providersRegisteredIn(Path jarPath) throws IOException {
    List<String> providers = new ArrayList<>();
    for (String line : readEntry(jarPath, RESOURCE_PROVIDER_SERVICE_FILE).split("\\R")) {
      String name = line.trim();
      if (!name.isEmpty() && !name.startsWith("#")) {
        providers.add(name);
      }
    }
    return providers;
  }

  @Test
  void bundlesTheGeneratedVersionResource() throws IOException {
    assertThat(entryNamesOf(AGENT_JAR)).contains("inst/dash0-distro-version.properties");
  }

  @Test
  void declaresTheAgentEntryPointsInTheManifest() throws IOException {
    try (JarFile jar = new JarFile(AGENT_JAR.toFile())) {
      Manifest manifest = jar.getManifest();

      assertThat(manifest.getMainAttributes().getValue("Premain-Class"))
          .isEqualTo("io.opentelemetry.javaagent.OpenTelemetryAgent");
      assertThat(manifest.getMainAttributes().getValue("Implementation-Vendor"))
          .isEqualTo("Dash0 Inc.");
      assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
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
      return Collections.unmodifiableList(names);
    }
  }

  private static String readEntry(Path jarPath, String entryName) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry entry = jar.getJarEntry(entryName);
      assertThat(entry).as("jar entry %s", entryName).isNotNull();
      try (InputStream input = jar.getInputStream(entry)) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
          buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
      }
    }
  }
}
