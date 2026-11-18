/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AlwaysOnSamplerModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AttributeNameValueModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ResourceModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SamplerModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.TracerProviderModel;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies to a declaratively configured SDK what {@link Dash0ConfigurationCustomizerProvider},
 * {@link DistroResourceProvider} and {@link Dash0PackageResourceProvider} apply to an SDK
 * configured from the environment.
 *
 * <p>See {@code docs/configuration.md}.
 */
@AutoService(DeclarativeConfigurationCustomizerProvider.class)
public final class Dash0DeclarativeConfigurationCustomizerProvider
    implements DeclarativeConfigurationCustomizerProvider {

  static final String DISABLE_PROPERTY = "dash0.disable";
  static final String DISABLE_VARIABLE = "DASH0_DISABLE";

  private static final Logger logger =
      Logger.getLogger(Dash0DeclarativeConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(DeclarativeConfigurationCustomizer customizer) {
    customizer.addModelCustomizer(
        model ->
            applyDash0Settings(
                model, systemPropertyOrEnvironmentVariable(DISABLE_PROPERTY, DISABLE_VARIABLE)));
  }

  /**
   * Rewrites {@code model} into what this distribution configures.
   *
   * @param disable the value of {@code DASH0_DISABLE}, or null when it is not set
   */
  static OpenTelemetryConfigurationModel applyDash0Settings(
      OpenTelemetryConfigurationModel model, String disable) {
    if (dash0DisableIsTrue(disable)) {
      logger.log(
          Level.INFO,
          "DASH0_DISABLE is set to true; the OpenTelemetry SDK is disabled and this process will "
              + "report no telemetry. The agent stays attached: set OTEL_JAVAAGENT_ENABLED=false "
              + "instead to avoid loading it at all.");
      model.setDisabled(Boolean.TRUE);
      return model;
    }

    addTheDash0ResourceAttributes(model);
    defaultTheSamplerToAlwaysOn(model);
    return model;
  }

  /**
   * Whether {@code value} is the string {@code true}, compared case-insensitively after trimming
   * whitespace.
   */
  static boolean dash0DisableIsTrue(String value) {
    return value != null && "true".equalsIgnoreCase(value.trim());
  }

  /**
   * Appends {@code telemetry.distro.*} and the attributes a Dash0 Linux package wrote beside the
   * agent jar to {@code resource.attributes}, skipping every name the file already declares.
   */
  static void addTheDash0ResourceAttributes(OpenTelemetryConfigurationModel model) {
    ResourceModel resource = model.getResource();
    if (resource == null) {
      resource = new ResourceModel();
      model.setResource(resource);
    }

    List<AttributeNameValueModel> attributes = resource.getAttributes();
    if (attributes == null) {
      attributes = new ArrayList<>();
      resource.setAttributes(attributes);
    }

    Set<String> declared = new HashSet<>();
    for (AttributeNameValueModel attribute : attributes) {
      declared.add(attribute.getName());
    }

    Resource dash0Attributes = DistroResource.get().merge(Dash0PackageResource.get());
    for (Map.Entry<AttributeKey<?>, Object> entry :
        dash0Attributes.getAttributes().asMap().entrySet()) {
      String name = entry.getKey().getKey();
      if (declared.contains(name)) {
        logger.log(Level.FINE, "The configuration file declares {0}, so its value is kept", name);
        continue;
      }
      attributes.add(new AttributeNameValueModel().setName(name).setValue(entry.getValue()));
    }
  }

  /** Sets {@code tracer_provider.sampler} to {@code always_on} unless the file declares one. */
  static void defaultTheSamplerToAlwaysOn(OpenTelemetryConfigurationModel model) {
    TracerProviderModel tracerProvider = model.getTracerProvider();
    if (tracerProvider == null) {
      tracerProvider = new TracerProviderModel();
      model.setTracerProvider(tracerProvider);
    }
    if (tracerProvider.getSampler() == null) {
      tracerProvider.setSampler(new SamplerModel().setAlwaysOn(new AlwaysOnSamplerModel()));
    }
  }

  /**
   * Returns the value of {@code systemProperty}, falling back to {@code environmentVariable} and
   * then to null, which is the precedence the autoconfigure SPI applies to the same two names.
   */
  static String systemPropertyOrEnvironmentVariable(
      String systemProperty, String environmentVariable) {
    String value = System.getProperty(systemProperty);
    return value != null ? value : System.getenv(environmentVariable);
  }
}
