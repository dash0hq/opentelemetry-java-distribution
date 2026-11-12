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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translates the Dash0 settings this distribution honours into the standard OpenTelemetry settings
 * the upstream agent already understands, and supplies the standard defaults it changes.
 *
 * <p>See {@code docs/configuration.md}.
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public final class Dash0ConfigurationCustomizerProvider
    implements AutoConfigurationCustomizerProvider {

  static final String DISABLE = "dash0.disable";
  static final String COLLECTOR_BASE_URL = "dash0.otel.collector.base.url";

  static final String SDK_DISABLED = "otel.sdk.disabled";
  static final String OTLP_ENDPOINT = "otel.exporter.otlp.endpoint";
  static final String TRACES_SAMPLER = "otel.traces.sampler";
  static final String ALWAYS_ON = "always_on";
  static final String PROPAGATORS = "otel.propagators";
  static final String TRACECONTEXT_BAGGAGE_XRAY = "tracecontext,baggage,xray";
  static final String SEMCONV_STABILITY_OPT_IN = "otel.semconv-stability.opt-in";
  static final String SEMCONV_STABILITY_OPT_IN_VARIABLE = "OTEL_SEMCONV_STABILITY_OPT_IN";
  static final String NEWER_SEMCONV_DOMAINS = "database,code,rpc,messaging";

  private static final Logger logger =
      Logger.getLogger(Dash0ConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    setSemconvStabilityOptInUnlessConfigured(System::getenv);
    autoConfiguration.addPropertiesSupplier(Dash0ConfigurationCustomizerProvider::dash0Defaults);
    autoConfiguration.addPropertiesCustomizer(Dash0ConfigurationCustomizerProvider::dash0Overrides);
  }

  static Map<String, String> dash0Defaults() {
    Map<String, String> defaults = new HashMap<>();
    defaults.put(TRACES_SAMPLER, ALWAYS_ON);
    // xray is last so that its context wins over traceparent, and so that it adds Lineage to the
    // extracted baggage rather than having the baggage propagator replace it.
    defaults.put(PROPAGATORS, TRACECONTEXT_BAGGAGE_XRAY);
    return defaults;
  }

  /**
   * Sets the system property {@code otel.semconv-stability.opt-in} to {@link
   * #NEWER_SEMCONV_DOMAINS} when neither that system property nor {@code
   * OTEL_SEMCONV_STABILITY_OPT_IN} already carries a value.
   *
   * @param environment reads an environment variable by name
   */
  static void setSemconvStabilityOptInUnlessConfigured(Function<String, String> environment) {
    if (!isBlank(System.getProperty(SEMCONV_STABILITY_OPT_IN))
        || !isBlank(environment.apply(SEMCONV_STABILITY_OPT_IN_VARIABLE))) {
      return;
    }
    logger.log(
        Level.FINE, "Opting in to the newer semantic conventions for {0}.", NEWER_SEMCONV_DOMAINS);
    System.setProperty(SEMCONV_STABILITY_OPT_IN, NEWER_SEMCONV_DOMAINS);
  }

  /**
   * Returns the standard settings the Dash0 settings imply, and nothing else. A setting that is
   * absent, empty or blank contributes no entry.
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

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
