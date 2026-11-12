/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** Compares the repackaged agent jar against the upstream jar it was built from. */
class AgentCompositionTest {

  private static final String DASH0_CLASSES = "inst/com/dash0/";
  private static final String VERSION_RESOURCE = "inst/dash0-distro-version.properties";
  private static final String ISOLATED_AGENT_TREE = "inst/";
  private static final String MERGED_SERVICE_FILES = "inst/META-INF/services/";
  private static final String LICENCE = "META-INF/LICENSE";

  @Test
  void dropsNothingUpstreamShips() throws IOException {
    Set<String> missing = entryNamesOf("dash0.upstream.agent.jar");
    missing.removeAll(entryNamesOf("dash0.agent.jar"));

    assertThat(missing).as("repackaging dropped entries that the upstream agent ships").isEmpty();
  }

  @Test
  void addsNothingBeyondTheDistributionsOwnFiles() throws IOException {
    Set<String> added = entryNamesOf("dash0.agent.jar");
    added.removeAll(entryNamesOf("dash0.upstream.agent.jar"));
    added.removeIf(
        name ->
            name.startsWith(DASH0_CLASSES)
                || name.equals(VERSION_RESOURCE)
                || name.equals(LICENCE));

    assertThat(added)
        .as(
            "the repackaged agent contains entries that are neither upstream's nor the"
                + " distribution's own. If these are wanted, say so here, because an extra copy of"
                + " a bundled library disables instrumentation with no error.")
        .isEmpty();
  }

  @Test
  void changesNothingUpstreamShipsInsideTheIsolatedAgentTree() throws IOException {
    Set<String> changed = new TreeSet<>();
    try (ZipFile mine = open("dash0.agent.jar");
        ZipFile theirs = open("dash0.upstream.agent.jar")) {
      Enumeration<? extends ZipEntry> entries = theirs.entries();
      while (entries.hasMoreElements()) {
        ZipEntry theirEntry = entries.nextElement();
        String name = theirEntry.getName();
        if (theirEntry.isDirectory()
            || !name.startsWith(ISOLATED_AGENT_TREE)
            || name.startsWith(MERGED_SERVICE_FILES)) {
          continue;
        }
        ZipEntry ourEntry = mine.getEntry(name);
        if (ourEntry != null
            && !Arrays.equals(contentOf(theirs, theirEntry), contentOf(mine, ourEntry))) {
          changed.add(name);
        }
      }
    }

    assertThat(changed)
        .as(
            "repackaging rewrote files upstream ships inside the isolated agent tree. A library"
                + " replaced by a different version looks exactly like this, and it disables"
                + " instrumentation with no error.")
        .isEmpty();
  }

  @Test
  void containsTheDistributionsOwnFiles() throws IOException {
    assertThat(entryNamesOf("dash0.agent.jar"))
        .as("the comparisons above mean nothing if the Dash0 files are not there at all")
        .contains(VERSION_RESOURCE)
        .contains(LICENCE)
        .anyMatch(name -> name.startsWith(DASH0_CLASSES));
  }

  private static ZipFile open(String jarProperty) throws IOException {
    return new ZipFile(Paths.get(System.getProperty(jarProperty)).toFile());
  }

  private static Set<String> entryNamesOf(String jarProperty) throws IOException {
    Set<String> names = new TreeSet<>();
    try (ZipFile zip = open(jarProperty)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        names.add(entries.nextElement().getName());
      }
    }
    return names;
  }

  private static byte[] contentOf(ZipFile zip, ZipEntry entry) throws IOException {
    try (InputStream in = zip.getInputStream(entry)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
    }
  }
}
