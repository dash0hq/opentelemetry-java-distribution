/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translates the Dash0 settings this distribution honours into the standard OpenTelemetry settings
 * the upstream agent already understands, and supplies the one standard default it changes on its
 * own, {@code otel.traces.sampler}.
 *
 * <p>Two Dash0 settings are translated, and only two. Both exist so that one {@code
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
 * <p>The two settings differ in what they beat, matching the Python and Ruby distributions.
 *
 * <p>{@code DASH0_DISABLE} overrides. Setting it wins over a {@code OTEL_SDK_DISABLED} that says
 * otherwise, because it is a kill switch and a kill switch that something else can quietly cancel
 * is not one. Python assigns {@code OTEL_SDK_DISABLED} unconditionally and Ruby refuses to boot at
 * all, neither of them consulting the standard name.
 *
 * <p>{@code DASH0_OTEL_COLLECTOR_BASE_URL} defaults. A {@code OTEL_EXPORTER_OTLP_ENDPOINT} the
 * operator set on the workload wins over it, because the Dash0 name is what one {@code
 * /etc/opentelemetry/injector/default_env.conf} puts on every host, and a host-wide default should
 * not silently beat a per-workload setting. Python expresses this as {@code environ.setdefault} and
 * Ruby as {@code Environment.set_default}.
 *
 * <p>Registration is {@code addPropertiesCustomizer} for both, and the endpoint gets its default
 * behaviour by checking whether {@code otel.exporter.otlp.endpoint} is already set rather than by
 * moving to {@code addPropertiesSupplier}. A supplier would give the right precedence, but its
 * signature takes no {@link ConfigProperties}, so the Dash0 names would have to be read straight
 * from {@link System#getenv}. That would drop the dotted system-property spelling and make the
 * logic untestable without mutating the environment.
 *
 * <p>{@code otel.traces.sampler} registers through {@code addPropertiesSupplier} instead, which
 * both system properties and environment variables beat. {@code docs/configuration.md} covers the
 * value and the choice of hook.
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
  static final String TRACES_SAMPLER = "otel.traces.sampler";

  /** Records and exports every span, whatever an incoming trace context says. */
  static final String ALWAYS_ON = "always_on";

  private static final Logger logger =
      Logger.getLogger(Dash0ConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addPropertiesSupplier(Dash0ConfigurationCustomizerProvider::dash0Defaults);
    autoConfiguration.addPropertiesCustomizer(Dash0ConfigurationCustomizerProvider::dash0Overrides);
  }

  /**
   * Returns the standard settings this distribution supplies as defaults, which anything set on the
   * workload beats.
   */
  static Map<String, String> dash0Defaults() {
    return Collections.singletonMap(TRACES_SAMPLER, ALWAYS_ON);
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
      String configuredEndpoint = config.getString(OTLP_ENDPOINT);
      if (configuredEndpoint != null && !configuredEndpoint.trim().isEmpty()) {
        logger.log(
            Level.FINE,
            "OTEL_EXPORTER_OTLP_ENDPOINT is set to {0}, so DASH0_OTEL_COLLECTOR_BASE_URL is left"
                + " unapplied.",
            configuredEndpoint);
      } else {
        String endpoint = baseUrl.trim().replaceAll("/+$", "");
        logger.log(
            Level.FINE, "Using DASH0_OTEL_COLLECTOR_BASE_URL as the OTLP endpoint: {0}", endpoint);
        overrides.put(OTLP_ENDPOINT, endpoint);
      }
    }

    return overrides;
  }
}
