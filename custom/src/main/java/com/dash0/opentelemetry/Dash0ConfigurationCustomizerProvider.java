/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translates the Dash0 settings this distribution honours into the standard OpenTelemetry settings
 * the upstream agent already understands.
 *
 * <p>Two settings are translated, and only two. Both exist so that one {@code
 * /etc/opentelemetry/injector/default_env.conf} on a host configures every Dash0 language
 * distribution the same way: without them a host that sets only the {@code DASH0_}-prefixed names
 * instruments its Node.js, Python and Ruby processes and silently does nothing useful for its JVMs.
 *
 * <table>
 *   <caption>Translations</caption>
 *   <tr><th>Dash0 setting</th><th>Becomes</th></tr>
 *   <tr><td>{@code DASH0_OTEL_COLLECTOR_BASE_URL}</td><td>{@code otel.exporter.otlp.endpoint}</td></tr>
 *   <tr><td>{@code DASH0_DISABLE}</td><td>{@code otel.sdk.disabled}</td></tr>
 * </table>
 *
 * <p>The names are read through {@link ConfigProperties} rather than from {@link System#getenv}.
 * The SDK lowercases an environment variable name and replaces each underscore with a dot, so
 * {@code DASH0_DISABLE} arrives as {@code dash0.disable}, and the system property {@code
 * -Ddash0.disable=true} works as well. That is how every other setting the agent reads behaves, and
 * it means these can be unit-tested without mutating the environment.
 *
 * <p>Registration uses {@code addPropertiesCustomizer} rather than {@code addPropertiesSupplier},
 * and the difference is load-bearing. A supplier contributes defaults, which an {@code
 * OTEL_}-prefixed environment variable then overrides; a customizer sees the resolved configuration
 * and its return value wins. The {@code DASH0_} name is documented to take precedence, matching the
 * Node.js, Python and Ruby distributions, so it has to be the customizer.
 *
 * <p>None of this applies under declarative configuration. When {@code OTEL_CONFIG_FILE} names a
 * file the SDK never consults the autoconfigure SPI, so this class is not invoked at all. See
 * {@code docs/configuration.md}.
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public final class Dash0ConfigurationCustomizerProvider
    implements AutoConfigurationCustomizerProvider {

  /** Disables all telemetry when set to {@code true}, case-insensitively. */
  static final String DISABLE = "dash0.disable";

  /** The base URL telemetry is sent to. */
  static final String COLLECTOR_BASE_URL = "dash0.otel.collector.base.url";

  static final String SDK_DISABLED = "otel.sdk.disabled";
  static final String OTLP_ENDPOINT = "otel.exporter.otlp.endpoint";

  private static final Logger logger =
      Logger.getLogger(Dash0ConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addPropertiesCustomizer(Dash0ConfigurationCustomizerProvider::dash0Overrides);
  }

  /**
   * Returns the standard settings the Dash0 settings imply, and nothing else.
   *
   * <p>A setting that is absent, empty or blank contributes no entry, so an operator who sets none
   * of them gets exactly the upstream agent's behaviour.
   *
   * @param config the resolved configuration, which carries {@code DASH0_} names in dotted form
   */
  static Map<String, String> dash0Overrides(ConfigProperties config) {
    Map<String, String> overrides = new HashMap<>();

    String disable = config.getString(DISABLE);
    if (disable != null && "true".equalsIgnoreCase(disable.trim())) {
      logger.log(
          Level.INFO,
          "DASH0_DISABLE is set to true; the OpenTelemetry SDK is disabled and this process will "
              + "report no telemetry. The agent stays attached: set OTEL_JAVAAGENT_ENABLED=false "
              + "instead to avoid loading it at all.");
      overrides.put(SDK_DISABLED, "true");
    }

    String baseUrl = config.getString(COLLECTOR_BASE_URL);
    if (baseUrl != null && !baseUrl.trim().isEmpty()) {
      String endpoint = baseUrl.trim().replaceAll("/+$", "");
      logger.log(
          Level.FINE, "Using DASH0_OTEL_COLLECTOR_BASE_URL as the OTLP endpoint: {0}", endpoint);
      overrides.put(OTLP_ENDPOINT, endpoint);
    }

    return overrides;
  }
}
