/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfiguration;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
  static final String RESOURCE_ATTRIBUTES_PROPERTY = "otel.resource.attributes";
  static final String RESOURCE_ATTRIBUTES_VARIABLE = "OTEL_RESOURCE_ATTRIBUTES";

  private static final Logger logger =
      Logger.getLogger(Dash0DefaultConfigurationFileProvider.class.getName());

  /** Returns the model parsed from the default configuration file. Never throws. */
  @Override
  public OpenTelemetryConfigurationModel getConfigurationModel() {
    try {
      OpenTelemetryConfigurationModel model =
          parseTheDefaultConfigurationFile(
              Dash0DeclarativeConfigurationCustomizerProvider.systemPropertyOrEnvironmentVariable(
                  CONFIGURATION_FILE_PROPERTY, CONFIGURATION_FILE_VARIABLE),
              Dash0DeclarativeConfigurationCustomizerProvider
                  .systemPropertyEnvironmentVariableOrAgentConfigurationFile(
                      SDK_DISABLED_PROPERTY, SDK_DISABLED_VARIABLE),
              Paths.get(
                  System.getProperty(
                      DEFAULT_CONFIGURATION_FILE_PROPERTY, DEFAULT_CONFIGURATION_FILE)));
      if (model != null) {
        String warning =
            warningAboutTheVariablesTheDefaultFileWillIgnore(
                Dash0DeclarativeConfigurationCustomizerProvider
                    .systemPropertyEnvironmentVariableOrAgentConfigurationFile(
                        Dash0DeclarativeConfigurationCustomizerProvider.OTLP_ENDPOINT_PROPERTY,
                        Dash0DeclarativeConfigurationCustomizerProvider.OTLP_ENDPOINT_VARIABLE),
                Dash0DeclarativeConfigurationCustomizerProvider
                    .systemPropertyEnvironmentVariableOrAgentConfigurationFile(
                        RESOURCE_ATTRIBUTES_PROPERTY, RESOURCE_ATTRIBUTES_VARIABLE));
        if (warning != null) {
          logger.log(Level.WARNING, warning);
        }
      }
      return model;
    } catch (RuntimeException e) {
      logger.log(
          Level.WARNING,
          "Could not look for "
              + DEFAULT_CONFIGURATION_FILE
              + "; the OpenTelemetry SDK is configured from the environment instead",
          e);
      return null;
    }
  }

  /**
   * Returns the configuration model parsed from {@code defaultConfigurationFile}, or null when
   * {@code configuredConfigurationFile} is set to a value that is not blank, when {@code
   * sdkDisabled} reads as true, when {@code defaultConfigurationFile} is not a regular file, when
   * it holds no configuration, or when reading or parsing it fails.
   *
   * @param configuredConfigurationFile the value of {@code OTEL_CONFIG_FILE}, or null when it is
   *     not set
   * @param sdkDisabled the value of {@code OTEL_SDK_DISABLED}, or null when it is not set
   * @param defaultConfigurationFile the path a Dash0 Linux package installs
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
        logger.log(
            Level.WARNING,
            "{0} holds no OpenTelemetry configuration. The OpenTelemetry SDK is configured from"
                + " the environment instead.",
            defaultConfigurationFile);
        return null;
      }
      logger.log(
          Level.INFO,
          "{0} is not set, so the OpenTelemetry SDK is configured from {1} instead of from the"
              + " environment.",
          new Object[] {CONFIGURATION_FILE_VARIABLE, defaultConfigurationFile});
      return model;
    } catch (IOException | RuntimeException e) {
      logger.log(
          Level.WARNING,
          defaultConfigurationFile
              + " could not be read as an OpenTelemetry configuration file. The OpenTelemetry SDK"
              + " is configured from the environment instead. Set "
              + CONFIGURATION_FILE_VARIABLE
              + " to configure it from a file.",
          e);
      return null;
    }
  }

  /**
   * Returns what to log when the default configuration file is about to configure the SDK and the
   * environment carries a setting it will ignore, or null when there is nothing to report.
   *
   * @param configuredOtlpEndpoint the value of {@code OTEL_EXPORTER_OTLP_ENDPOINT}, or null when it
   *     is not set
   * @param configuredResourceAttributes the value of {@code OTEL_RESOURCE_ATTRIBUTES}, or null when
   *     it is not set
   */
  static String warningAboutTheVariablesTheDefaultFileWillIgnore(
      String configuredOtlpEndpoint, String configuredResourceAttributes) {
    List<String> ignored = new ArrayList<>();
    if (isSet(configuredOtlpEndpoint)) {
      ignored.add(
          Dash0DeclarativeConfigurationCustomizerProvider.OTLP_ENDPOINT_VARIABLE
              + " is set to "
              + configuredOtlpEndpoint.trim());
    }
    if (isSet(configuredResourceAttributes)) {
      ignored.add(
          RESOURCE_ATTRIBUTES_VARIABLE + " is set to " + configuredResourceAttributes.trim());
    }
    if (ignored.isEmpty()) {
      return null;
    }
    return String.join(", ", ignored)
        + ". A declaratively configured SDK reads no OTEL_EXPORTER_OTLP_* variable and no "
        + RESOURCE_ATTRIBUTES_VARIABLE
        + ". "
        + DEFAULT_CONFIGURATION_FILE
        + " decides where this process exports and what resource attributes it carries. Name them"
        + " in that file, or set "
        + CONFIGURATION_FILE_VARIABLE
        + " to a file of your own.";
  }

  private static boolean isSet(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
