/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The resource attributes written by whatever installed the agent jar, read from {@value
 * #PROPERTIES_FILE_NAME} in the directory holding that jar.
 */
public final class Dash0PackageResource {

  static final String PROPERTIES_FILE_NAME = "dash0-resource-attributes.properties";

  private static final Logger logger = Logger.getLogger(Dash0PackageResource.class.getName());

  private Dash0PackageResource() {}

  /**
   * Returns the attributes the properties file declares. Never throws: every failure degrades to an
   * empty resource.
   */
  public static Resource get() {
    try {
      Path directory = directoryHoldingThisClassesJar();
      if (directory == null) {
        return Resource.empty();
      }
      return resourceFrom(directory.resolve(PROPERTIES_FILE_NAME));
    } catch (RuntimeException e) {
      logger.log(
          Level.WARNING,
          "Could not look for " + PROPERTIES_FILE_NAME + "; its attributes will be missing",
          e);
      return Resource.empty();
    }
  }

  /**
   * @return the directory holding the jar this class was loaded from, or null when it was not
   *     loaded from a jar on the file system
   */
  private static Path directoryHoldingThisClassesJar() {
    CodeSource codeSource = Dash0PackageResource.class.getProtectionDomain().getCodeSource();
    URL location = codeSource == null ? null : codeSource.getLocation();
    if (location == null) {
      logger.log(
          Level.FINE,
          "Cannot determine where this class was loaded from, so {0} will not be read",
          PROPERTIES_FILE_NAME);
      return null;
    }

    if (!"file".equals(location.getProtocol())) {
      logger.log(
          Level.FINE,
          "This class was loaded from {0}, which is not a file, so {1} will not be read",
          new Object[] {location, PROPERTIES_FILE_NAME});
      return null;
    }

    try {
      return Paths.get(location.toURI()).getParent();
    } catch (URISyntaxException | RuntimeException e) {
      logger.log(
          Level.FINE,
          "Cannot turn "
              + location
              + " into a file path, so "
              + PROPERTIES_FILE_NAME
              + " will not be read",
          e);
      return null;
    }
  }

  /**
   * Reads one attribute per property from {@code propertiesFile}.
   *
   * @return the attributes the file declares, or an empty resource when the file does not exist or
   *     cannot be read
   */
  static Resource resourceFrom(Path propertiesFile) {
    if (!Files.isRegularFile(propertiesFile)) {
      return Resource.empty();
    }

    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(propertiesFile)) {
      properties.load(in);
    } catch (IOException | IllegalArgumentException e) {
      logger.log(
          Level.WARNING, "Cannot read " + propertiesFile + "; its attributes are missing", e);
      return Resource.empty();
    }

    AttributesBuilder attributes = Attributes.builder();
    for (String name : properties.stringPropertyNames()) {
      String value = properties.getProperty(name);
      if (!name.isEmpty() && value != null && !value.isEmpty()) {
        attributes.put(name, value);
      }
    }
    return Resource.create(attributes.build());
  }
}
