/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
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
 * <p>The Dash0 Node.js, Python and Ruby distributions are started by a script the Linux package
 * owns, so that script appends {@code dash0.package.name} and {@code dash0.package.version} to
 * {@code OTEL_RESOURCE_ATTRIBUTES} before the SDK starts. A Java agent has no such script: the
 * OpenTelemetry injector contributes only {@code -javaagent:}, so there is nowhere for the package
 * to put those attributes.
 *
 * <p>Instead the package writes {@value #PROPERTIES_FILE_NAME} into the same directory as the agent
 * jar, and this provider reads it. Every key in that file becomes a resource attribute whose value
 * is the property value, which keeps the set of attributes a property of the package rather than of
 * this code.
 *
 * <p>This class does not implement {@code Ordered}, so its order is {@code 0} and anything else
 * setting the same key wins: an upstream detector, or {@code OTEL_RESOURCE_ATTRIBUTES}. That is
 * deliberate, because a value the operator set on the workload should beat one the package baked
 * in. {@code DistroResourceProvider} makes the opposite choice, and says so.
 *
 * <p>The file is absent in every other way of running the agent, and its absence is not an error.
 *
 * <p>This works only in the repackaged agent jar, which is the shape the Linux packages install.
 * Loaded from the standalone extension jar, the class reports an {@code otel:} code source served
 * by the agent's own class loader rather than a file, so no directory can be resolved and no
 * attributes are contributed. See {@code docs/resource-detection.md}.
 */
@AutoService(ResourceProvider.class)
public final class Dash0PackageResourceProvider implements ResourceProvider {

  /** The file this provider reads, resolved against the directory holding the agent jar. */
  static final String PROPERTIES_FILE_NAME = "dash0-resource-attributes.properties";

  private static final Logger logger =
      Logger.getLogger(Dash0PackageResourceProvider.class.getName());

  /**
   * {@inheritDoc}
   *
   * <p>Never throws. A resource provider that throws aborts SDK autoconfiguration, which takes the
   * whole agent down with it and loses every signal, including the ones this distribution has
   * nothing to do with. No attribute is worth that, so every failure here degrades to an empty
   * resource.
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
   * Locates the directory containing the jar this class was loaded from.
   *
   * @return that directory, or null when this class was not loaded from a jar on the file system
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

    // Only a file: URL can become a Path. Loaded from the standalone extension jar, this class
    // reports an otel: URL, which the agent's own class loader serves and no FileSystemProvider
    // understands; Paths.get would throw FileSystemNotFoundException.
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
