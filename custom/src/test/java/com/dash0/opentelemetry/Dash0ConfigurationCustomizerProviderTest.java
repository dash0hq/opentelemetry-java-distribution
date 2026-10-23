/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.COLLECTOR_BASE_URL;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.DISABLE;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.OTLP_ENDPOINT;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.SDK_DISABLED;
import static com.dash0.opentelemetry.Dash0ConfigurationCustomizerProvider.dash0Overrides;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Dash0ConfigurationCustomizerProviderTest {

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
  void appliesBothSettingsTogether() {
    Map<String, String> properties = new HashMap<>();
    properties.put(DISABLE, "true");
    properties.put(COLLECTOR_BASE_URL, "https://ingress.dash0.com");

    assertThat(dash0Overrides(configOf(properties)))
        .containsEntry(SDK_DISABLED, "true")
        .containsEntry(OTLP_ENDPOINT, "https://ingress.dash0.com");
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
