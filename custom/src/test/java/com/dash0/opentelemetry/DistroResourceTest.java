/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DistroResourceTest {

  private static final AttributeKey<String> TELEMETRY_DISTRO_NAME =
      AttributeKey.stringKey("telemetry.distro.name");
  private static final AttributeKey<String> TELEMETRY_DISTRO_VERSION =
      AttributeKey.stringKey("telemetry.distro.version");

  @Test
  void reportsTheDash0DistroName() {
    Resource resource = DistroResource.get();

    assertThat(resource.getAttribute(TELEMETRY_DISTRO_NAME)).isEqualTo("dash0-java");
  }

  @Test
  void reportsTheVersionGeneratedByTheBuild() {
    Resource resource = DistroResource.get();

    // The build writes the real project version into the jar. Asserting it is
    // not the fallback is what catches the generateVersionResource task silently
    // not running, which would otherwise ship telemetry tagged "unknown".
    assertThat(resource.getAttribute(TELEMETRY_DISTRO_VERSION))
        .isNotNull()
        .isNotEqualTo(DistroResource.UNKNOWN_VERSION);
  }

  @Test
  void readsTheVersionFromTheGeneratedResource() {
    ClassLoader stubbed = classLoaderServing("version=1.2.3\n");

    assertThat(DistroResource.readBundledDistroVersion(stubbed)).isEqualTo("1.2.3");
  }

  @Test
  void fallsBackToUnknownWhenTheVersionResourceIsAbsent() {
    ClassLoader stubbed = classLoaderServing(null);

    assertThat(DistroResource.readBundledDistroVersion(stubbed))
        .isEqualTo(DistroResource.UNKNOWN_VERSION);
  }

  @Test
  void fallsBackToUnknownWhenTheVersionResourceHasNoVersionKey() {
    ClassLoader stubbed = classLoaderServing("something.else=1\n");

    assertThat(DistroResource.readBundledDistroVersion(stubbed))
        .isEqualTo(DistroResource.UNKNOWN_VERSION);
  }

  /** A loader that serves the given contents for the version resource, or nothing when null. */
  private static ClassLoader classLoaderServing(String versionResourceContents) {
    return new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(String name) {
        if (!DistroResource.VERSION_RESOURCE_NAME.equals(name) || versionResourceContents == null) {
          return null;
        }
        return new ByteArrayInputStream(versionResourceContents.getBytes(StandardCharsets.UTF_8));
      }
    };
  }
}
