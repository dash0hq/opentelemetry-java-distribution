/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfiguration;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configures the SDK from {@value #DEFAULT_CONFIGURATION_FILE}, the path a Dash0 Linux package
 * installs, when {@code OTEL_CONFIG_FILE} is unset and that file exists.
 *
 * <p>See {@code docs/configuration.md}.
 */
@AutoService(DeclarativeConfigurationProvider.class)
public final class Dash0DefaultConfigurationFileProvider
    implements DeclarativeConfigurationProvider {

  static final String DEFAULT_CONFIGURATION_FILE = "/etc/opentelemetry/java/otel-config.yaml";
  static final String DEFAULT_CONFIGURATION_FILE_PROPERTY = "dash0.default.configuration.file";
  static final String CONFIGURATION_FILE_PROPERTY = "otel.config.file";
  static final String CONFIGURATION_FILE_VARIABLE = "OTEL_CONFIG_FILE";
  static final String SDK_DISABLED_PROPERTY = "otel.sdk.disabled";
  static final String SDK_DISABLED_VARIABLE = "OTEL_SDK_DISABLED";

  private static final Logger logger =
      Logger.getLogger(Dash0DefaultConfigurationFileProvider.class.getName());

  /** Returns the model parsed from the default configuration file. */
  @Override
  public OpenTelemetryConfigurationModel getConfigurationModel() {
    return parseTheDefaultConfigurationFile(
        Dash0DeclarativeConfigurationCustomizerProvider.systemPropertyOrEnvironmentVariable(
            CONFIGURATION_FILE_PROPERTY, CONFIGURATION_FILE_VARIABLE),
        Dash0DeclarativeConfigurationCustomizerProvider
            .systemPropertyEnvironmentVariableOrAgentConfigurationFile(
                SDK_DISABLED_PROPERTY, SDK_DISABLED_VARIABLE),
        Paths.get(
            System.getProperty(DEFAULT_CONFIGURATION_FILE_PROPERTY, DEFAULT_CONFIGURATION_FILE)));
  }

  /**
   * Returns the configuration model parsed from {@code defaultConfigurationFile}, or null when
   * {@code configuredConfigurationFile} is set to a value that is not blank, when {@code
   * sdkDisabled} reads as true, or when {@code defaultConfigurationFile} is not a regular file.
   *
   * @param configuredConfigurationFile the value of {@code OTEL_CONFIG_FILE}, or null when it is
   *     not set
   * @param sdkDisabled the value of {@code OTEL_SDK_DISABLED}, or null when it is not set
   * @param defaultConfigurationFile the path a Dash0 Linux package installs
   * @throws ConfigurationException if {@code defaultConfigurationFile} cannot be read or holds no
   *     configuration
   */
  static OpenTelemetryConfigurationModel parseTheDefaultConfigurationFile(
      String configuredConfigurationFile, String sdkDisabled, Path defaultConfigurationFile) {
    if (configuredConfigurationFile != null && !configuredConfigurationFile.trim().isEmpty()) {
      return null;
    }
    if (Boolean.parseBoolean(sdkDisabled)) {
      return null;
    }
    if (!Files.isRegularFile(defaultConfigurationFile)) {
      return null;
    }
    try (InputStream configurationFile = Files.newInputStream(defaultConfigurationFile)) {
      OpenTelemetryConfigurationModel model = DeclarativeConfiguration.parse(configurationFile);
      if (model == null) {
        throw new ConfigurationException(
            defaultConfigurationFile + " holds no OpenTelemetry configuration");
      }
      logger.log(
          Level.INFO,
          "{0} is not set, so the OpenTelemetry SDK is configured from {1} instead of from the"
              + " environment.",
          new Object[] {CONFIGURATION_FILE_VARIABLE, defaultConfigurationFile});
      return model;
    } catch (IOException e) {
      throw new ConfigurationException(
          defaultConfigurationFile + " could not be read as an OpenTelemetry configuration file",
          e);
    }
  }
}
