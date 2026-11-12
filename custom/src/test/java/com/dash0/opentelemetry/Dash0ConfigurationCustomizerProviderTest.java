/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.ALWAYS_ON;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.COLLECTOR_BASE_URL;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.DISABLE;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.NEWER_SEMCONV_DOMAINS;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.OTLP_ENDPOINT;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.SDK_DISABLED;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.SEMCONV_STABILITY_OPT_IN;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.SEMCONV_STABILITY_OPT_IN_VARIABLE;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.TRACES_SAMPLER;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.dash0Defaults;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.dash0Overrides;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.setSemconvStabilityOptInUnlessConfigured;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Dash0ConfigurationCustomizerProviderTest {

  private String inheritedSemconvStabilityOptIn;

  @BeforeEach
  void clearTheSemconvStabilityOptInSystemProperty() {
    inheritedSemconvStabilityOptIn = System.getProperty(SEMCONV_STABILITY_OPT_IN);
    System.clearProperty(SEMCONV_STABILITY_OPT_IN);
  }

  @AfterEach
  void restoreTheSemconvStabilityOptInSystemProperty() {
    if (inheritedSemconvStabilityOptIn == null) {
      System.clearProperty(SEMCONV_STABILITY_OPT_IN);
    } else {
      System.setProperty(SEMCONV_STABILITY_OPT_IN, inheritedSemconvStabilityOptIn);
    }
  }

  @Test
  void isRegisteredAsAnAutoConfigurationCustomizerProviderService() {
    assertThat(ServiceLoader.load(AutoConfigurationCustomizerProvider.class))
        .anyMatch(Dash0ConfigurationCustomizerProvider.class::isInstance);
  }

  @Test
  void overridesNothingWhenNoDash0SettingIsPresent() {
    assertThat(dash0Overrides(configOf(Collections.emptyMap()))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "True", "  true  "})
  void disablesTheSdkWhenDisableIsTrue(String value) {
    assertThat(dash0Overrides(configOf(Collections.singletonMap(DISABLE, value))))
        .containsEntry(SDK_DISABLED, "true");
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "1", "yes", "on", ""})
  void leavesTheSdkEnabledForAnyOtherDisableValue(String value) {
    assertThat(dash0Overrides(configOf(Collections.singletonMap(DISABLE, value))))
        .doesNotContainKey(SDK_DISABLED);
  }

  @Test
  void usesTheCollectorBaseUrlAsTheOtlpEndpoint() {
    assertThat(
            dash0Overrides(
                configOf(
                    Collections.singletonMap(
                        COLLECTOR_BASE_URL, "https://ingress.eu-west-1.aws.dash0.com"))))
        .containsEntry(OTLP_ENDPOINT, "https://ingress.eu-west-1.aws.dash0.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://ingress.dash0.com/", "https://ingress.dash0.com///"})
  void stripsTrailingSlashesFromTheCollectorBaseUrl(String value) {
    assertThat(dash0Overrides(configOf(Collections.singletonMap(COLLECTOR_BASE_URL, value))))
        .containsEntry(OTLP_ENDPOINT, "https://ingress.dash0.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void ignoresABlankCollectorBaseUrl(String value) {
    assertThat(dash0Overrides(configOf(Collections.singletonMap(COLLECTOR_BASE_URL, value))))
        .doesNotContainKey(OTLP_ENDPOINT);
  }

  @Test
  void leavesAUserSetOtlpEndpointAlone() {
    Map<String, String> properties = new HashMap<>();
    properties.put(COLLECTOR_BASE_URL, "https://ingress.dash0.com");
    properties.put(OTLP_ENDPOINT, "http://collector.internal:4318");

    assertThat(dash0Overrides(configOf(properties)))
        .as(
            "DASH0_OTEL_COLLECTOR_BASE_URL is a host-wide default, so a workload's own"
                + " OTEL_EXPORTER_OTLP_ENDPOINT beats it, as in the Python and Ruby distributions")
        .doesNotContainKey(OTLP_ENDPOINT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void appliesTheCollectorBaseUrlWhenTheOtlpEndpointIsBlank(String value) {
    Map<String, String> properties = new HashMap<>();
    properties.put(COLLECTOR_BASE_URL, "https://ingress.dash0.com");
    properties.put(OTLP_ENDPOINT, value);

    assertThat(dash0Overrides(configOf(properties)))
        .containsEntry(OTLP_ENDPOINT, "https://ingress.dash0.com");
  }

  @Test
  void disableStillBeatsAUserSetSdkDisabled() {
    Map<String, String> properties = new HashMap<>();
    properties.put(DISABLE, "true");
    properties.put(SDK_DISABLED, "false");

    assertThat(dash0Overrides(configOf(properties)))
        .as("DASH0_DISABLE is a kill switch; the standard name must not be able to cancel it")
        .containsEntry(SDK_DISABLED, "true");
  }

  @Test
  void defaultsTheSamplerToAlwaysOn() {
    assertThat(dash0Defaults()).containsEntry(TRACES_SAMPLER, ALWAYS_ON);
  }

  @Test
  void neverReturnsTheSamplerAsAnOverride() {
    Map<String, String> properties = new HashMap<>();
    properties.put(TRACES_SAMPLER, "parentbased_traceidratio");

    assertThat(dash0Overrides(configOf(properties)))
        .as(
            "the sampler is registered through addPropertiesSupplier, which system properties and"
                + " environment variables beat. Returning it here would make it an override and"
                + " silently discard the workload's own OTEL_TRACES_SAMPLER")
        .doesNotContainKey(TRACES_SAMPLER);
  }

  @Test
  void registersTheSamplerThroughTheSupplierAndNotThroughTheCustomizer() {
    RecordingCustomizer recording = new RecordingCustomizer();

    new Dash0ConfigurationCustomizerProvider().customize(recording);

    Map<String, String> supplied = new HashMap<>();
    for (Supplier<Map<String, String>> supplier : recording.suppliers) {
      supplied.putAll(supplier.get());
    }

    Map<String, String> overridden = new HashMap<>();
    ConfigProperties config =
        configOf(Collections.singletonMap(TRACES_SAMPLER, "parentbased_traceidratio"));
    for (Function<ConfigProperties, Map<String, String>> customizer : recording.customizers) {
      overridden.putAll(customizer.apply(config));
    }

    assertThat(supplied)
        .as(
            "addPropertiesSupplier loses to both system properties and environment variables, which"
                + " is what makes always_on a default rather than a decision taken away from the"
                + " workload")
        .containsEntry(TRACES_SAMPLER, ALWAYS_ON);
    assertThat(overridden)
        .as(
            "addPropertiesCustomizer beats both, so registering the sampler there would silently"
                + " discard the workload's own OTEL_TRACES_SAMPLER")
        .doesNotContainKey(TRACES_SAMPLER);
  }

  @Test
  void defaultsTheSemconvStabilityOptInWhenNothingConfiguresIt() {
    setSemconvStabilityOptInUnlessConfigured(anEmptyEnvironment());

    assertThat(System.getProperty(SEMCONV_STABILITY_OPT_IN)).isEqualTo(NEWER_SEMCONV_DOMAINS);
  }

  @Test
  void leavesASystemPropertySemconvStabilityOptInAlone() {
    System.setProperty(SEMCONV_STABILITY_OPT_IN, "database");

    setSemconvStabilityOptInUnlessConfigured(anEmptyEnvironment());

    assertThat(System.getProperty(SEMCONV_STABILITY_OPT_IN)).isEqualTo("database");
  }

  @Test
  void leavesAnEnvironmentVariableSemconvStabilityOptInAlone() {
    setSemconvStabilityOptInUnlessConfigured(
        name -> SEMCONV_STABILITY_OPT_IN_VARIABLE.equals(name) ? "database" : null);

    assertThat(System.getProperty(SEMCONV_STABILITY_OPT_IN))
        .as(
            "the agent reads the environment variable itself, so setting the system property here"
                + " would shadow it and discard the operator's own value")
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void defaultsTheSemconvStabilityOptInOverABlankSystemProperty(String value) {
    System.setProperty(SEMCONV_STABILITY_OPT_IN, value);

    setSemconvStabilityOptInUnlessConfigured(anEmptyEnvironment());

    assertThat(System.getProperty(SEMCONV_STABILITY_OPT_IN)).isEqualTo(NEWER_SEMCONV_DOMAINS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void defaultsTheSemconvStabilityOptInOverABlankEnvironmentVariable(String value) {
    setSemconvStabilityOptInUnlessConfigured(
        name -> SEMCONV_STABILITY_OPT_IN_VARIABLE.equals(name) ? value : null);

    assertThat(System.getProperty(SEMCONV_STABILITY_OPT_IN)).isEqualTo(NEWER_SEMCONV_DOMAINS);
  }

  @Test
  void neverReturnsTheSemconvStabilityOptInThroughEitherPropertyHook() {
    RecordingCustomizer recording = new RecordingCustomizer();

    new Dash0ConfigurationCustomizerProvider().customize(recording);

    Map<String, String> supplied = new HashMap<>();
    for (Supplier<Map<String, String>> supplier : recording.suppliers) {
      supplied.putAll(supplier.get());
    }
    Map<String, String> overridden = new HashMap<>();
    for (Function<ConfigProperties, Map<String, String>> customizer : recording.customizers) {
      overridden.putAll(customizer.apply(configOf(Collections.emptyMap())));
    }

    assertThat(supplied)
        .as(
            "the agent resolves otel.semconv-stability.opt-in through"
                + " SemconvSelectionResolver, which reads it with SystemProperty.getString and so"
                + " consults only System.getProperty and System.getenv. A value returned from"
                + " either property hook reaches ConfigProperties and nothing else, so it changes"
                + " no attribute and reports nothing.")
        .doesNotContainKey(SEMCONV_STABILITY_OPT_IN);
    assertThat(overridden).doesNotContainKey(SEMCONV_STABILITY_OPT_IN);
  }

  @Test
  void appliesBothSettingsTogether() {
    Map<String, String> properties = new HashMap<>();
    properties.put(DISABLE, "true");
    properties.put(COLLECTOR_BASE_URL, "https://ingress.dash0.com");

    assertThat(dash0Overrides(configOf(properties)))
        .containsEntry(SDK_DISABLED, "true")
        .containsEntry(OTLP_ENDPOINT, "https://ingress.dash0.com");
  }

  /**
   * An {@link AutoConfigurationCustomizer} that keeps what was registered with it instead of
   * configuring anything.
   *
   * <p>The two property hooks record what is registered with them. The four hooks this distribution
   * does not use throw, so a change that starts customizing a sampler or a resource fails here
   * rather than going unnoticed.
   */
  private static final class RecordingCustomizer implements AutoConfigurationCustomizer {

    final List<Supplier<Map<String, String>>> suppliers = new ArrayList<>();
    final List<Function<ConfigProperties, Map<String, String>>> customizers = new ArrayList<>();

    @Override
    public AutoConfigurationCustomizer addPropertiesSupplier(
        Supplier<Map<String, String>> supplier) {
      suppliers.add(supplier);
      return this;
    }

    @Override
    public AutoConfigurationCustomizer addPropertiesCustomizer(
        Function<ConfigProperties, Map<String, String>> customizer) {
      customizers.add(customizer);
      return this;
    }

    @Override
    public AutoConfigurationCustomizer addPropagatorCustomizer(
        BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator>
            customizer) {
      throw new UnsupportedOperationException("addPropagatorCustomizer");
    }

    @Override
    public AutoConfigurationCustomizer addResourceCustomizer(
        BiFunction<? super Resource, ConfigProperties, ? extends Resource> customizer) {
      throw new UnsupportedOperationException("addResourceCustomizer");
    }

    @Override
    public AutoConfigurationCustomizer addSamplerCustomizer(
        BiFunction<? super Sampler, ConfigProperties, ? extends Sampler> customizer) {
      throw new UnsupportedOperationException("addSamplerCustomizer");
    }

    @Override
    public AutoConfigurationCustomizer addSpanExporterCustomizer(
        BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter> customizer) {
      throw new UnsupportedOperationException("addSpanExporterCustomizer");
    }
  }

  /** An environment in which no variable is set. */
  private static Function<String, String> anEmptyEnvironment() {
    return name -> null;
  }

  /**
   * A {@link ConfigProperties} holding exactly the given values.
   *
   * <p>Only {@code getString} is reachable from the code under test; every other accessor throws
   * rather than returning a default, so a change that starts reading configuration some other way
   * fails loudly here instead of silently seeing nothing.
   */
  private static ConfigProperties configOf(Map<String, String> values) {
    return new ConfigProperties() {
      @Override
      public String getString(String name) {
        return values.get(name);
      }

      @Override
      public Boolean getBoolean(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public Integer getInt(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public Long getLong(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public Double getDouble(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public Duration getDuration(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public List<String> getList(String name) {
        throw new UnsupportedOperationException(name);
      }

      @Override
      public Map<String, String> getMap(String name) {
        throw new UnsupportedOperationException(name);
      }
    };
  }
}
