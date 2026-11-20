/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Checks that the X-Ray propagator the unit tests run against is the one the upstream agent
 * bundles, so those tests describe what ships.
 */
class AwsXrayPropagatorVersionTest {

  private static final String BUNDLED_VERSION_FILE =
      "inst/io/opentelemetry/javaagent/shaded/io/opentelemetry/contrib/awsxray/propagator/"
          + "version.properties";

  @Test
  void testDependencyMatchesTheVersionTheUpstreamAgentBundles() throws Exception {
    Properties bundled = new Properties();
    try (ZipFile agent =
        new ZipFile(Paths.get(System.getProperty("dash0.upstream.agent.jar")).toFile())) {
      ZipEntry entry = agent.getEntry(BUNDLED_VERSION_FILE);
      assertThat(entry)
          .as(
              "the upstream agent no longer bundles the X-Ray propagator at %s",
              BUNDLED_VERSION_FILE)
          .isNotNull();
      try (InputStream in = agent.getInputStream(entry)) {
        bundled.load(in);
      }
    }

    assertThat(System.getProperty("dash0.aws.xray.propagator.version"))
        .as(
            "opentelemetryAwsXrayPropagator in gradle/libs.versions.toml must match the version"
                + " the upstream agent bundles")
        .isEqualTo(bundled.getProperty("contrib.version"));
  }
}
