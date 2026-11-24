/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.Ordered;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
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
 * Contributes the resource attributes written by whatever installed the agent jar.
 *
 * <p>The package writes {@value #PROPERTIES_FILE_NAME} into the same directory as the agent jar,
 * and every key in that file becomes a resource attribute. The file is absent in every other way of
 * running the agent, and its absence is not an error. Anything else setting the same key wins.
 *
 * <p>This works only in the repackaged agent jar. See {@code docs/resource-detection.md}.
 */
@AutoService(ResourceProvider.class)
public final class Dash0PackageResourceProvider implements ResourceProvider, Ordered {

  static final String PROPERTIES_FILE_NAME = "dash0-resource-attributes.properties";

  /** Applied before every other provider, so every one of them can overwrite these attributes. */
  @Override
  public int order() {
    return Integer.MIN_VALUE;
  }

  private static final Logger logger =
      Logger.getLogger(Dash0PackageResourceProvider.class.getName());

  /**
   * {@inheritDoc}
   *
   * <p>Never throws. Every failure degrades to an empty resource.
   */
  @Override
  public Resource createResource(ConfigProperties config) {
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
    CodeSource codeSource =
        Dash0PackageResourceProvider.class.getProtectionDomain().getCodeSource();
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
