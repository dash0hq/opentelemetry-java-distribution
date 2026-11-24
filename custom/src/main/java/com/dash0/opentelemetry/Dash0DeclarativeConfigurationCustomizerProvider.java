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
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LogRecordExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LogRecordProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LoggerProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.MeterProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.MetricReaderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.PropagatorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.PushMetricExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ResourceModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SamplerModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SpanExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SpanProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.TracerProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalHostResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalProcessResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalResourceDetectionModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ResourceModelAccessor;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
  static final String COLLECTOR_BASE_URL_PROPERTY = "dash0.otel.collector.base.url";
  static final String COLLECTOR_BASE_URL_VARIABLE = "DASH0_OTEL_COLLECTOR_BASE_URL";
  static final String OTLP_ENDPOINT_PROPERTY = "otel.exporter.otlp.endpoint";
  static final String OTLP_ENDPOINT_VARIABLE = "OTEL_EXPORTER_OTLP_ENDPOINT";

  static final String TRACES_PATH = "/v1/traces";
  static final String METRICS_PATH = "/v1/metrics";
  static final String LOGS_PATH = "/v1/logs";

  static final String OTLP_HTTP_DEFAULT_ENDPOINT = "http://localhost:4318";
  static final String OTLP_GRPC_DEFAULT_ENDPOINT = "http://localhost:4317";

  private static final Logger logger =
      Logger.getLogger(Dash0DeclarativeConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(DeclarativeConfigurationCustomizer customizer) {
    customizer.addModelCustomizer(
        model ->
            applyDash0Settings(
                model,
                systemPropertyOrEnvironmentVariable(DISABLE_PROPERTY, DISABLE_VARIABLE),
                systemPropertyOrEnvironmentVariable(
                    COLLECTOR_BASE_URL_PROPERTY, COLLECTOR_BASE_URL_VARIABLE),
                systemPropertyOrEnvironmentVariable(
                    OTLP_ENDPOINT_PROPERTY, OTLP_ENDPOINT_VARIABLE)));
  }

  /**
   * Rewrites {@code model} into what this distribution configures.
   *
   * @param disable the value of {@code DASH0_DISABLE}, or null when it is not set
   * @param collectorBaseUrl the value of {@code DASH0_OTEL_COLLECTOR_BASE_URL}, or null when it is
   *     not set
   * @param configuredOtlpEndpoint the value of {@code OTEL_EXPORTER_OTLP_ENDPOINT}, or null when it
   *     is not set
   */
  static OpenTelemetryConfigurationModel applyDash0Settings(
      OpenTelemetryConfigurationModel model,
      String disable,
      String collectorBaseUrl,
      String configuredOtlpEndpoint) {
    if (dash0DisableIsTrue(disable)) {
      logger.log(
          Level.INFO,
          "DASH0_DISABLE is set to true; the OpenTelemetry SDK is disabled and this process will "
              + "report no telemetry. The agent stays attached: set OTEL_JAVAAGENT_ENABLED=false "
              + "instead to avoid loading it at all.");
      model.setDisabled(Boolean.TRUE);
      return model;
    }

    addResourceAttributesTheFileDoesNotDeclare(
        model, Dash0PackageResource.get().merge(DistroResource.get()));
    enableTheHostAndProcessDetectors(model);
    defaultTheSamplerToAlwaysOn(model);
    defaultThePropagatorsToTracecontextBaggageXray(model);
    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
        model, collectorBaseUrl, configuredOtlpEndpoint);
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
   * Appends every attribute of {@code dash0Attributes} to {@code resource.attributes}, skipping
   * every name the file already declares.
   */
  static void addResourceAttributesTheFileDoesNotDeclare(
      OpenTelemetryConfigurationModel model, Resource dash0Attributes) {
    ResourceModel resource = resourceModelCreatingItWhenAbsent(model);

    List<AttributeNameValueModel> attributes = resource.getAttributes();
    if (attributes == null) {
      attributes = new ArrayList<>();
      resource.setAttributes(attributes);
    }

    Set<String> declared = new HashSet<>();
    for (AttributeNameValueModel attribute : attributes) {
      declared.add(attribute.getName());
    }
    declared.addAll(namesIn(resource.getAttributesList()));

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

  /**
   * The attribute names {@code DefaultConfigProperties.getMap} reads out of {@code attributesList},
   * a comma separated list of {@code name=value} pairs in the format {@code
   * OTEL_RESOURCE_ATTRIBUTES} uses.
   */
  static Set<String> namesIn(String attributesList) {
    Set<String> names = new LinkedHashSet<>();
    if (attributesList == null) {
      return names;
    }
    for (String pair : attributesList.split(",")) {
      int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String name = pair.substring(0, separator).trim();
      String value = pair.substring(separator + 1).trim();
      if (!name.isEmpty() && !value.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Adds the upstream {@code host} and {@code process} resource detectors to {@code
   * resource.detection/development}, each one only when the file does not already name it, and each
   * one before every detector the file names.
   */
  static void enableTheHostAndProcessDetectors(OpenTelemetryConfigurationModel model) {
    ResourceModel resource = resourceModelCreatingItWhenAbsent(model);

    ExperimentalResourceDetectionModel detection = ResourceModelAccessor.getDetection(resource);
    if (detection == null) {
      detection = new ExperimentalResourceDetectionModel();
    }

    List<ExperimentalResourceDetectorModel> detectors = detection.getDetectors();
    if (detectors == null) {
      detectors = new ArrayList<>();
      detection.setDetectors(detectors);
    }

    boolean hostIsNamed = false;
    boolean processIsNamed = false;
    for (ExperimentalResourceDetectorModel detector : detectors) {
      hostIsNamed = hostIsNamed || detector.getHost() != null;
      processIsNamed = processIsNamed || detector.getProcess() != null;
    }

    if (!hostIsNamed) {
      detectors.add(
          0,
          new ExperimentalResourceDetectorModel()
              .setHost(new ExperimentalHostResourceDetectorModel()));
    }
    if (!processIsNamed) {
      detectors.add(
          0,
          new ExperimentalResourceDetectorModel()
              .setProcess(new ExperimentalProcessResourceDetectorModel()));
    }

    ResourceModelAccessor.setDetection(resource, detection);
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
   * Sets {@code propagator.composite_list} to {@value
   * Dash0ConfigurationCustomizerProvider#TRACECONTEXT_BAGGAGE_XRAY} unless the file declares a
   * {@code composite} or a {@code composite_list}.
   */
  static void defaultThePropagatorsToTracecontextBaggageXray(
      OpenTelemetryConfigurationModel model) {
    PropagatorModel propagator = model.getPropagator();
    if (propagator == null) {
      propagator = new PropagatorModel();
      model.setPropagator(propagator);
    }
    if (propagator.getComposite() == null && propagator.getCompositeList() == null) {
      propagator.setCompositeList(Dash0ConfigurationCustomizerProvider.TRACECONTEXT_BAGGAGE_XRAY);
    }
  }

  /**
   * Points every OTLP exporter the file declares without an {@code endpoint} at {@code
   * collectorBaseUrl}, leaving the file alone when {@code configuredOtlpEndpoint} is set.
   *
   * @param collectorBaseUrl the value of {@code DASH0_OTEL_COLLECTOR_BASE_URL}, whose trailing
   *     slashes are stripped
   * @param configuredOtlpEndpoint the value of {@code OTEL_EXPORTER_OTLP_ENDPOINT}
   */
  static void useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
      OpenTelemetryConfigurationModel model,
      String collectorBaseUrl,
      String configuredOtlpEndpoint) {
    if (collectorBaseUrl == null || collectorBaseUrl.trim().isEmpty()) {
      return;
    }
    boolean suppressed = configuredOtlpEndpoint != null && !configuredOtlpEndpoint.trim().isEmpty();

    String baseUrl = suppressed ? null : collectorBaseUrl.trim().replaceAll("/+$", "");
    if (!suppressed) {
      logger.log(
          Level.FINE, "Using DASH0_OTEL_COLLECTOR_BASE_URL as the OTLP endpoint: {0}", baseUrl);
    }
    Set<String> defaultsLeftInPlace = new LinkedHashSet<>();

    TracerProviderModel tracerProvider = model.getTracerProvider();
    if (tracerProvider != null && tracerProvider.getProcessors() != null) {
      for (SpanProcessorModel processor : tracerProvider.getProcessors()) {
        if (processor.getBatch() != null) {
          applyTheBaseUrlToASpanExporterOrRecordItsDefault(
              processor.getBatch().getExporter(), baseUrl, defaultsLeftInPlace);
        }
        if (processor.getSimple() != null) {
          applyTheBaseUrlToASpanExporterOrRecordItsDefault(
              processor.getSimple().getExporter(), baseUrl, defaultsLeftInPlace);
        }
      }
    }

    MeterProviderModel meterProvider = model.getMeterProvider();
    if (meterProvider != null && meterProvider.getReaders() != null) {
      for (MetricReaderModel reader : meterProvider.getReaders()) {
        if (reader.getPeriodic() != null) {
          applyTheBaseUrlToAMetricExporterOrRecordItsDefault(
              reader.getPeriodic().getExporter(), baseUrl, defaultsLeftInPlace);
        }
      }
    }

    LoggerProviderModel loggerProvider = model.getLoggerProvider();
    if (loggerProvider != null && loggerProvider.getProcessors() != null) {
      for (LogRecordProcessorModel processor : loggerProvider.getProcessors()) {
        if (processor.getBatch() != null) {
          applyTheBaseUrlToALogRecordExporterOrRecordItsDefault(
              processor.getBatch().getExporter(), baseUrl, defaultsLeftInPlace);
        }
        if (processor.getSimple() != null) {
          applyTheBaseUrlToALogRecordExporterOrRecordItsDefault(
              processor.getSimple().getExporter(), baseUrl, defaultsLeftInPlace);
        }
      }
    }

    if (suppressed && !defaultsLeftInPlace.isEmpty()) {
      logger.log(
          Level.WARNING,
          "OTEL_EXPORTER_OTLP_ENDPOINT is set to {0}, so DASH0_OTEL_COLLECTOR_BASE_URL is left"
              + " unapplied. A declaratively configured SDK reads no OTEL_* variable, so every"
              + " OTLP exporter the configuration file left without an endpoint falls back to"
              + " its specification default, {1}. Name the endpoint in the configuration file"
              + " instead.",
          new Object[] {configuredOtlpEndpoint.trim(), String.join(" and ", defaultsLeftInPlace)});
    }
  }

  /**
   * Sets {@code exporter}'s endpoint to {@code baseUrl} plus {@value #TRACES_PATH} for the HTTP
   * exporter and to {@code baseUrl} alone for the gRPC one, or, when {@code baseUrl} is null, adds
   * to {@code defaultsLeftInPlace} the specification default each one keeps.
   */
  static void applyTheBaseUrlToASpanExporterOrRecordItsDefault(
      SpanExporterModel exporter, String baseUrl, Set<String> defaultsLeftInPlace) {
    if (exporter == null) {
      return;
    }
    if (exporter.getOtlpHttp() != null && exporter.getOtlpHttp().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_HTTP_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpHttp().setEndpoint(baseUrl + TRACES_PATH);
      }
    }
    if (exporter.getOtlpGrpc() != null && exporter.getOtlpGrpc().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_GRPC_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpGrpc().setEndpoint(baseUrl);
      }
    }
  }

  /**
   * Sets {@code exporter}'s endpoint to {@code baseUrl} plus {@value #METRICS_PATH} for the HTTP
   * exporter and to {@code baseUrl} alone for the gRPC one, or, when {@code baseUrl} is null, adds
   * to {@code defaultsLeftInPlace} the specification default each one keeps.
   */
  static void applyTheBaseUrlToAMetricExporterOrRecordItsDefault(
      PushMetricExporterModel exporter, String baseUrl, Set<String> defaultsLeftInPlace) {
    if (exporter == null) {
      return;
    }
    if (exporter.getOtlpHttp() != null && exporter.getOtlpHttp().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_HTTP_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpHttp().setEndpoint(baseUrl + METRICS_PATH);
      }
    }
    if (exporter.getOtlpGrpc() != null && exporter.getOtlpGrpc().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_GRPC_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpGrpc().setEndpoint(baseUrl);
      }
    }
  }

  /**
   * Sets {@code exporter}'s endpoint to {@code baseUrl} plus {@value #LOGS_PATH} for the HTTP
   * exporter and to {@code baseUrl} alone for the gRPC one, or, when {@code baseUrl} is null, adds
   * to {@code defaultsLeftInPlace} the specification default each one keeps.
   */
  static void applyTheBaseUrlToALogRecordExporterOrRecordItsDefault(
      LogRecordExporterModel exporter, String baseUrl, Set<String> defaultsLeftInPlace) {
    if (exporter == null) {
      return;
    }
    if (exporter.getOtlpHttp() != null && exporter.getOtlpHttp().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_HTTP_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpHttp().setEndpoint(baseUrl + LOGS_PATH);
      }
    }
    if (exporter.getOtlpGrpc() != null && exporter.getOtlpGrpc().getEndpoint() == null) {
      if (baseUrl == null) {
        defaultsLeftInPlace.add(OTLP_GRPC_DEFAULT_ENDPOINT);
      } else {
        exporter.getOtlpGrpc().setEndpoint(baseUrl);
      }
    }
  }

  /** Returns {@code model}'s resource, attaching an empty one when the file declares none. */
  private static ResourceModel resourceModelCreatingItWhenAbsent(
      OpenTelemetryConfigurationModel model) {
    ResourceModel resource = model.getResource();
    if (resource == null) {
      resource = new ResourceModel();
      model.setResource(resource);
    }
    return resource;
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
