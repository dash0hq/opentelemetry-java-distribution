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
 * <p>The file is absent in every other way of running the agent, and its absence is not an error.
 */
@AutoService(ResourceProvider.class)
public final class Dash0PackageResourceProvider implements ResourceProvider {

  /** The file this provider reads, resolved against the directory holding the agent jar. */
  static final String PROPERTIES_FILE_NAME = "dash0-resource-attributes.properties";

  private static final Logger logger =
      Logger.getLogger(Dash0PackageResourceProvider.class.getName());

  @Override
  public Resource createResource(ConfigProperties config) {
    CodeSource codeSource =
        Dash0PackageResourceProvider.class.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      logger.log(
          Level.FINE,
          "Cannot determine the location of the agent jar, so {0} will not be read",
          PROPERTIES_FILE_NAME);
      return Resource.empty();
    }

    Path agentJar;
    try {
      agentJar = Paths.get(codeSource.getLocation().toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      logger.log(
          Level.FINE,
          "The agent jar location is not a file path, so "
              + PROPERTIES_FILE_NAME
              + " will not be read",
          e);
      return Resource.empty();
    }

    Path directory = agentJar.getParent();
    if (directory == null) {
      return Resource.empty();
    }

    return resourceFrom(directory.resolve(PROPERTIES_FILE_NAME));
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
