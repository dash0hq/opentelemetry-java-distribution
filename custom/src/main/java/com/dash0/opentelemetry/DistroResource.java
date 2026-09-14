/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code telemetry.distro.*} resource attributes that identify this distribution.
 *
 * <p>The upstream agent already reports {@code telemetry.distro.name} as {@code
 * opentelemetry-java-instrumentation}. This resource replaces that value with {@code dash0-java},
 * so telemetry produced through this distribution can be attributed to it and filtered by its
 * version.
 */
public final class DistroResource {

  /** The value reported as {@code telemetry.distro.name}. */
  public static final String DISTRO_NAME = "dash0-java";

  /** Reported as {@code telemetry.distro.version} when the version cannot be determined. */
  static final String UNKNOWN_VERSION = "unknown";

  /** Generated into the jar by the {@code generateVersionResource} Gradle task. */
  static final String VERSION_RESOURCE_NAME = "dash0-distro-version.properties";

  private static final AttributeKey<String> TELEMETRY_DISTRO_NAME =
      AttributeKey.stringKey("telemetry.distro.name");
  private static final AttributeKey<String> TELEMETRY_DISTRO_VERSION =
      AttributeKey.stringKey("telemetry.distro.version");

  private static final Logger logger = Logger.getLogger(DistroResource.class.getName());

  private static final Resource INSTANCE =
      Resource.create(
          Attributes.of(
              TELEMETRY_DISTRO_NAME,
              DISTRO_NAME,
              TELEMETRY_DISTRO_VERSION,
              readBundledDistroVersion(DistroResource.class.getClassLoader())));

  private DistroResource() {}

  /** Returns the resource describing this distribution. */
  public static Resource get() {
    return INSTANCE;
  }

  /**
   * Reads the distribution version from the properties file the build generates into the jar.
   *
   * <p>Returns {@link #UNKNOWN_VERSION} when the file is absent or unreadable. Not being able to
   * determine the version must never stop the distribution from starting, so this reports the
   * failure at {@code FINE} and carries on.
   *
   * @param classLoader the loader to read the generated resource from
   */
  static String readBundledDistroVersion(ClassLoader classLoader) {
    try (InputStream input = classLoader.getResourceAsStream(VERSION_RESOURCE_NAME)) {
      if (input == null) {
        logger.log(
            Level.FINE, "Distribution version resource {0} not found", VERSION_RESOURCE_NAME);
        return UNKNOWN_VERSION;
      }
      Properties properties = new Properties();
      properties.load(input);
      return properties.getProperty("version", UNKNOWN_VERSION);
    } catch (IOException e) {
      logger.log(Level.FINE, "Could not read the distribution version", e);
      return UNKNOWN_VERSION;
    }
  }
}
