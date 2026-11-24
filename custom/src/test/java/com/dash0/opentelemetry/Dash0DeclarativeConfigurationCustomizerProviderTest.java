/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.DISABLE_PROPERTY;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.DISABLE_VARIABLE;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.addResourceAttributesTheFileDoesNotDeclare;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.applyDash0Settings;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.dash0DisableIsTrue;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.defaultThePropagatorsToTracecontextBaggageXray;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.defaultTheSamplerToAlwaysOn;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.enableTheHostAndProcessDetectors;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.systemPropertyOrEnvironmentVariable;
import static com.dash0.opentelemetry.Dash0DeclarativeConfigurationCustomizerProvider.useTheCollectorBaseUrlForEveryDeclaredOtlpExporter;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AlwaysOffSamplerModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AttributeNameValueModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.B3MultiPropagatorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.BatchLogRecordProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.BatchSpanProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ConsoleExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LogRecordExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LogRecordProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LoggerProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.MeterProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.MetricReaderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpGrpcExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpGrpcMetricExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpMetricExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.PeriodicMetricReaderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.PropagatorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.PushMetricExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ResourceModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SamplerModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SimpleLogRecordProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SimpleSpanProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SpanExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SpanProcessorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.TextMapPropagatorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.TracerProviderModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalContainerResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalHostResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalResourceDetectionModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ExperimentalResourceDetectorModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal.ResourceModelAccessor;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Dash0DeclarativeConfigurationCustomizerProviderTest {

  private static final String COLLECTOR_BASE_URL = "https://ingress.eu-west-1.aws.dash0.com";

  @Test
  void isRegisteredAsADeclarativeConfigurationCustomizerProviderService() {
    assertThat(ServiceLoader.load(DeclarativeConfigurationCustomizerProvider.class))
        .anyMatch(Dash0DeclarativeConfigurationCustomizerProvider.class::isInstance);
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "True", "  true  "})
  void disablesTheSdkWhenDisableIsTrue(String value) {
    assertThat(dash0DisableIsTrue(value)).isTrue();
    assertThat(applyDash0Settings(new OpenTelemetryConfigurationModel(), value, null, null))
        .extracting(OpenTelemetryConfigurationModel::getDisabled)
        .isEqualTo(Boolean.TRUE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "1", "yes", "on", ""})
  void leavesTheSdkEnabledForAnyOtherDisableValue(String value) {
    assertThat(dash0DisableIsTrue(value)).isFalse();

    OpenTelemetryConfigurationModel model =
        applyDash0Settings(new OpenTelemetryConfigurationModel(), value, null, null);

    assertThat(model.getDisabled()).isNull();
    assertThat(attributesOf(model))
        .as("a dead customizer satisfies any assertion phrased as an absence")
        .containsKey("telemetry.distro.name");
  }

  @Test
  void leavesTheSdkEnabledWhenDisableIsNotSetAtAll() {
    assertThat(dash0DisableIsTrue(null)).isFalse();
    assertThat(applyDash0Settings(new OpenTelemetryConfigurationModel(), null, null, null))
        .extracting(OpenTelemetryConfigurationModel::getDisabled)
        .isNull();
  }

  @Test
  void disableStillBeatsADisabledFalseInTheFile() {
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel().setDisabled(Boolean.FALSE);

    assertThat(applyDash0Settings(model, "true", null, null).getDisabled())
        .as("DASH0_DISABLE is a kill switch; the file must not be able to cancel it")
        .isEqualTo(Boolean.TRUE);
  }

  @Test
  void touchesNothingElseWhenDisabled() {
    OpenTelemetryConfigurationModel model =
        applyDash0Settings(new OpenTelemetryConfigurationModel(), "true", COLLECTOR_BASE_URL, null);

    assertThat(model.getResource()).isNull();
    assertThat(model.getTracerProvider()).isNull();
    assertThat(model.getPropagator()).isNull();
  }

  @Test
  void addsTheDistroAttributesWhenTheFileDeclaresNoResource() {
    OpenTelemetryConfigurationModel model =
        applyDash0Settings(new OpenTelemetryConfigurationModel(), null, null, null);

    assertThat(attributesOf(model))
        .containsEntry("telemetry.distro.name", DistroResource.DISTRO_NAME)
        .containsKey("telemetry.distro.version");
  }

  @Test
  void addsThePackageAttributesBesideTheOnesTheFileDeclares() {
    OpenTelemetryConfigurationModel model = modelDeclaring(attribute("service.name", "checkout"));

    addResourceAttributesTheFileDoesNotDeclare(
        model,
        Resource.create(
            Attributes.builder()
                .put("dash0.package.name", "dash0-java-autoinstrumentation")
                .put("dash0.package.version", "1.2.3")
                .build()));

    assertThat(attributesOf(model))
        .containsEntry("service.name", "checkout")
        .containsEntry("dash0.package.name", "dash0-java-autoinstrumentation")
        .containsEntry("dash0.package.version", "1.2.3");
  }

  @Test
  void keepsAnAttributeTheFileAlreadyDeclares() {
    OpenTelemetryConfigurationModel model =
        modelDeclaring(attribute("telemetry.distro.name", "mine"));

    addResourceAttributesTheFileDoesNotDeclare(
        model,
        Resource.create(Attributes.builder().put("telemetry.distro.name", "theirs").build()));

    assertThat(attributesOf(model))
        .containsOnlyKeys("telemetry.distro.name")
        .containsEntry("telemetry.distro.name", "mine");
  }

  @Test
  void keepsAnAttributeTheFileDeclaresThroughAttributesList() {
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setResource(
                new ResourceModel()
                    .setAttributesList(" service.name=checkout , dash0.package.name=mine "));

    addResourceAttributesTheFileDoesNotDeclare(
        model,
        Resource.create(
            Attributes.builder()
                .put("dash0.package.name", "theirs")
                .put("dash0.package.version", "1.2.3")
                .build()));

    assertThat(attributesOf(model))
        .containsOnlyKeys("dash0.package.version")
        .containsEntry("dash0.package.version", "1.2.3");
  }

  @Test
  void enablesTheHostAndProcessDetectors() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();

    enableTheHostAndProcessDetectors(model);

    List<ExperimentalResourceDetectorModel> detectors = detectorsOf(model);
    assertThat(detectors).hasSize(2);
    assertThat(detectors.get(0).getProcess()).isNotNull();
    assertThat(detectors.get(1).getHost()).isNotNull();
  }

  @Test
  void putsTheHostAndProcessDetectorsBeforeEveryDetectorTheFileNames() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();
    declareDetectors(
        model,
        new ExperimentalResourceDetectorModel()
            .setContainer(new ExperimentalContainerResourceDetectorModel()));

    enableTheHostAndProcessDetectors(model);

    List<ExperimentalResourceDetectorModel> detectors = detectorsOf(model);
    assertThat(detectors).hasSize(3);
    assertThat(detectors.get(0).getProcess()).isNotNull();
    assertThat(detectors.get(1).getHost()).isNotNull();
    assertThat(detectors.get(2).getContainer())
        .as("a detector the operator chose is applied last, so it wins")
        .isNotNull();
  }

  @Test
  void leavesTheHostDetectorTheFileNamesAlone() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();
    declareDetectors(
        model,
        new ExperimentalResourceDetectorModel()
            .setHost(new ExperimentalHostResourceDetectorModel()));

    enableTheHostAndProcessDetectors(model);

    List<ExperimentalResourceDetectorModel> detectors = detectorsOf(model);
    assertThat(detectors).hasSize(2);
    assertThat(detectors.get(0).getProcess()).isNotNull();
    assertThat(detectors.get(1).getHost()).isNotNull();
  }

  @Test
  void defaultsTheSamplerToAlwaysOn() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();

    defaultTheSamplerToAlwaysOn(model);

    assertThat(model.getTracerProvider().getSampler().getAlwaysOn()).isNotNull();
  }

  @Test
  void keepsTheSamplerTheFileDeclares() {
    SamplerModel alwaysOff = new SamplerModel().setAlwaysOff(new AlwaysOffSamplerModel());
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setTracerProvider(new TracerProviderModel().setSampler(alwaysOff));

    defaultTheSamplerToAlwaysOn(model);

    assertThat(model.getTracerProvider().getSampler()).isSameAs(alwaysOff);
  }

  @Test
  void defaultsThePropagatorsToTracecontextBaggageXray() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();

    defaultThePropagatorsToTracecontextBaggageXray(model);

    assertThat(model.getPropagator().getCompositeList())
        .as("the same value Dash0ConfigurationCustomizerProvider supplies as otel.propagators")
        .isEqualTo(Dash0ConfigurationCustomizerProvider.TRACECONTEXT_BAGGAGE_XRAY);
  }

  @Test
  void keepsTheCompositeTheFileDeclares() {
    List<TextMapPropagatorModel> composite =
        Collections.singletonList(
            new TextMapPropagatorModel().setB3multi(new B3MultiPropagatorModel()));
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setPropagator(new PropagatorModel().setComposite(composite));

    defaultThePropagatorsToTracecontextBaggageXray(model);

    assertThat(model.getPropagator().getComposite()).isSameAs(composite);
    assertThat(model.getPropagator().getCompositeList()).isNull();
  }

  @Test
  void keepsTheCompositeListTheFileDeclares() {
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setPropagator(new PropagatorModel().setCompositeList("b3multi"));

    defaultThePropagatorsToTracecontextBaggageXray(model);

    assertThat(model.getPropagator().getCompositeList()).isEqualTo("b3multi");
  }

  @Test
  void setsTheSignalPathOnEveryHttpExporterTheFileDeclares() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/traces");
    assertThat(metricExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/metrics");
    assertThat(logRecordExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/logs");
  }

  @Test
  void setsNoSignalPathOnAGrpcExporter() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpGrpcExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
        model, "http://collector.internal:4317", null);

    assertThat(spanExporterOf(model).getOtlpGrpc().getEndpoint())
        .as("a gRPC endpoint carries no signal path, unlike an HTTP one")
        .isEqualTo("http://collector.internal:4317");
    assertThat(metricExporterOf(model).getOtlpGrpc().getEndpoint())
        .isEqualTo("http://collector.internal:4317");
    assertThat(logRecordExporterOf(model).getOtlpGrpc().getEndpoint())
        .isEqualTo("http://collector.internal:4317");
  }

  @Test
  void reachesAnExporterUnderASimpleProcessor() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();
    SpanExporterModel spanExporter =
        new SpanExporterModel().setOtlpHttp(new OtlpHttpExporterModel());
    LogRecordExporterModel logRecordExporter =
        new LogRecordExporterModel().setOtlpHttp(new OtlpHttpExporterModel());
    model.setTracerProvider(
        new TracerProviderModel()
            .setProcessors(
                Collections.singletonList(
                    new SpanProcessorModel()
                        .setSimple(new SimpleSpanProcessorModel().setExporter(spanExporter)))));
    model.setLoggerProvider(
        new LoggerProviderModel()
            .setProcessors(
                Collections.singletonList(
                    new LogRecordProcessorModel()
                        .setSimple(
                            new SimpleLogRecordProcessorModel().setExporter(logRecordExporter)))));

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(spanExporter.getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/traces");
    assertThat(logRecordExporter.getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/logs");
  }

  @Test
  void keepsTheEndpointTheFileDeclares() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();
    spanExporterOf(model).getOtlpHttp().setEndpoint("http://declared.invalid:4318/v1/traces");

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo("http://declared.invalid:4318/v1/traces");
    assertThat(metricExporterOf(model).getOtlpHttp().getEndpoint())
        .as("the other signals are still filled in")
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/metrics");
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://ingress.dash0.com/", "https://ingress.dash0.com///"})
  void stripsTrailingSlashesFromTheCollectorBaseUrl(String value) {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, value, null);

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo("https://ingress.dash0.com/v1/traces");
  }

  @Test
  void saysNothingWhenTheFileNamesEveryEndpointItself() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();
    spanExporterOf(model).getOtlpHttp().setEndpoint("http://declared.invalid:4318/v1/traces");
    metricExporterOf(model).getOtlpHttp().setEndpoint("http://declared.invalid:4318/v1/metrics");
    logRecordExporterOf(model).getOtlpHttp().setEndpoint("http://declared.invalid:4318/v1/logs");

    List<LogRecord> logged =
        whileCapturingTheCustomizersLog(
            () ->
                useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
                    model, COLLECTOR_BASE_URL, "http://collector.internal:4318"));

    assertThat(warningsIn(logged))
        .as(
            "DASH0_OTEL_COLLECTOR_BASE_URL arrives host-wide, so warning every workload that"
                + " already names its endpoints would warn on a correct configuration")
        .isEmpty();
  }

  @Test
  void saysNothingWhenTheFileDeclaresNoOtlpExporterAtAll() {
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setTracerProvider(
                new TracerProviderModel()
                    .setProcessors(
                        Collections.singletonList(
                            new SpanProcessorModel()
                                .setBatch(
                                    new BatchSpanProcessorModel()
                                        .setExporter(
                                            new SpanExporterModel()
                                                .setConsole(new ConsoleExporterModel()))))));

    List<LogRecord> logged =
        whileCapturingTheCustomizersLog(
            () ->
                useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
                    model, COLLECTOR_BASE_URL, "http://collector.internal:4318"));

    assertThat(warningsIn(logged)).isEmpty();
  }

  @Test
  void namesTheHttpDefaultForAnHttpExporterLeftWithoutAnEndpoint() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    List<LogRecord> logged =
        whileCapturingTheCustomizersLog(
            () ->
                useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
                    model, COLLECTOR_BASE_URL, "http://collector.internal:4318"));

    assertThat(warningsIn(logged)).hasSize(1);
    assertThat(warningsIn(logged).get(0).getParameters()[1])
        .isEqualTo(Dash0DeclarativeConfigurationCustomizerProvider.OTLP_HTTP_DEFAULT_ENDPOINT);
  }

  @Test
  void namesTheGrpcDefaultForAGrpcExporterLeftWithoutAnEndpoint() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpGrpcExporterForEverySignal();

    List<LogRecord> logged =
        whileCapturingTheCustomizersLog(
            () ->
                useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
                    model, COLLECTOR_BASE_URL, "http://collector.internal:4317"));

    assertThat(warningsIn(logged)).hasSize(1);
    assertThat(warningsIn(logged).get(0).getParameters()[1])
        .as("a gRPC endpoint carries no signal path and its default port is 4317, not 4318")
        .isEqualTo(Dash0DeclarativeConfigurationCustomizerProvider.OTLP_GRPC_DEFAULT_ENDPOINT);
  }

  @Test
  void leavesTheFileAloneWhenTheOtlpEndpointIsAlreadySet() {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(
        model, COLLECTOR_BASE_URL, "http://collector.internal:4318");

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint())
        .as(
            "DASH0_OTEL_COLLECTOR_BASE_URL is a host-wide default, so a workload's own"
                + " OTEL_EXPORTER_OTLP_ENDPOINT beats it, as on the environment path")
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void ignoresABlankCollectorBaseUrl(String value) {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, value, null);

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void appliesTheCollectorBaseUrlWhenTheOtlpEndpointIsBlank(String value) {
    OpenTelemetryConfigurationModel model = modelWithAnOtlpHttpExporterForEverySignal();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, value);

    assertThat(spanExporterOf(model).getOtlpHttp().getEndpoint())
        .isEqualTo(COLLECTOR_BASE_URL + "/v1/traces");
  }

  @Test
  void leavesAConsoleExporterAlone() {
    SpanExporterModel console = new SpanExporterModel().setConsole(new ConsoleExporterModel());
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel()
            .setTracerProvider(
                new TracerProviderModel()
                    .setProcessors(
                        Collections.singletonList(
                            new SpanProcessorModel()
                                .setBatch(new BatchSpanProcessorModel().setExporter(console)))));

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(console.getOtlpHttp()).isNull();
    assertThat(console.getOtlpGrpc()).isNull();
  }

  @Test
  void theEndpointMappingInventsNoProviderTheFileDoesNotDeclare() {
    OpenTelemetryConfigurationModel model = new OpenTelemetryConfigurationModel();

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(model.getTracerProvider()).isNull();
    assertThat(model.getMeterProvider()).isNull();
    assertThat(model.getLoggerProvider()).isNull();
  }

  @Test
  void theDefaultsAttachTheirOwnNodeAndNoExporter() {
    OpenTelemetryConfigurationModel model =
        applyDash0Settings(new OpenTelemetryConfigurationModel(), null, COLLECTOR_BASE_URL, null);

    assertThat(model.getTracerProvider().getSampler().getAlwaysOn()).isNotNull();
    assertThat(model.getPropagator().getCompositeList()).isNotNull();
    assertThat(model.getTracerProvider().getProcessors())
        .as(
            "a default needs a node to live in; it must not start an export the file never asked"
                + " for")
        .isNull();
    assertThat(model.getMeterProvider()).isNull();
    assertThat(model.getLoggerProvider()).isNull();
  }

  @Test
  void addsNoProcessorToAProviderThatDeclaresNone() {
    OpenTelemetryConfigurationModel model =
        new OpenTelemetryConfigurationModel().setTracerProvider(new TracerProviderModel());

    useTheCollectorBaseUrlForEveryDeclaredOtlpExporter(model, COLLECTOR_BASE_URL, null);

    assertThat(model.getTracerProvider().getProcessors()).isNull();
  }

  @Test
  void readsTheSystemPropertyBeforeTheEnvironmentVariable() {
    System.setProperty(DISABLE_PROPERTY, "true");
    try {
      assertThat(systemPropertyOrEnvironmentVariable(DISABLE_PROPERTY, DISABLE_VARIABLE))
          .isEqualTo("true");
    } finally {
      System.clearProperty(DISABLE_PROPERTY);
    }
  }

  @Test
  void readsNothingWhenNeitherTheSystemPropertyNorTheVariableIsSet() {
    assertThat(
            systemPropertyOrEnvironmentVariable("dash0.no.such.setting", "DASH0_NO_SUCH_SETTING"))
        .isNull();
  }

  private static List<LogRecord> whileCapturingTheCustomizersLog(Runnable action) {
    List<LogRecord> captured = new ArrayList<>();
    Logger logger =
        Logger.getLogger(Dash0DeclarativeConfigurationCustomizerProvider.class.getName());
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            captured.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    Level previous = logger.getLevel();
    logger.setLevel(Level.ALL);
    try {
      action.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previous);
    }
    return captured;
  }

  private static List<LogRecord> warningsIn(List<LogRecord> records) {
    List<LogRecord> warnings = new ArrayList<>();
    for (LogRecord record : records) {
      if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
        warnings.add(record);
      }
    }
    return warnings;
  }

  private static OpenTelemetryConfigurationModel modelDeclaring(
      AttributeNameValueModel... attributes) {
    return new OpenTelemetryConfigurationModel()
        .setResource(new ResourceModel().setAttributes(new ArrayList<>(Arrays.asList(attributes))));
  }

  private static AttributeNameValueModel attribute(String name, String value) {
    return new AttributeNameValueModel().setName(name).setValue(value);
  }

  private static Map<String, Object> attributesOf(OpenTelemetryConfigurationModel model) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (AttributeNameValueModel attribute : model.getResource().getAttributes()) {
      attributes.put(attribute.getName(), attribute.getValue());
    }
    return attributes;
  }

  private static void declareDetectors(
      OpenTelemetryConfigurationModel model, ExperimentalResourceDetectorModel... detectors) {
    ResourceModel resource = new ResourceModel();
    ResourceModelAccessor.setDetection(
        resource,
        new ExperimentalResourceDetectionModel()
            .setDetectors(new ArrayList<>(Arrays.asList(detectors))));
    model.setResource(resource);
  }

  private static List<ExperimentalResourceDetectorModel> detectorsOf(
      OpenTelemetryConfigurationModel model) {
    return ResourceModelAccessor.getDetection(model.getResource()).getDetectors();
  }

  private static OpenTelemetryConfigurationModel modelWithAnOtlpHttpExporterForEverySignal() {
    return modelExportingWith(
        new SpanExporterModel().setOtlpHttp(new OtlpHttpExporterModel()),
        new PushMetricExporterModel().setOtlpHttp(new OtlpHttpMetricExporterModel()),
        new LogRecordExporterModel().setOtlpHttp(new OtlpHttpExporterModel()));
  }

  private static OpenTelemetryConfigurationModel modelWithAnOtlpGrpcExporterForEverySignal() {
    return modelExportingWith(
        new SpanExporterModel().setOtlpGrpc(new OtlpGrpcExporterModel()),
        new PushMetricExporterModel().setOtlpGrpc(new OtlpGrpcMetricExporterModel()),
        new LogRecordExporterModel().setOtlpGrpc(new OtlpGrpcExporterModel()));
  }

  private static OpenTelemetryConfigurationModel modelExportingWith(
      SpanExporterModel spanExporter,
      PushMetricExporterModel metricExporter,
      LogRecordExporterModel logRecordExporter) {
    return new OpenTelemetryConfigurationModel()
        .setTracerProvider(
            new TracerProviderModel()
                .setProcessors(
                    Collections.singletonList(
                        new SpanProcessorModel()
                            .setBatch(new BatchSpanProcessorModel().setExporter(spanExporter)))))
        .setMeterProvider(
            new MeterProviderModel()
                .setReaders(
                    Collections.singletonList(
                        new MetricReaderModel()
                            .setPeriodic(
                                new PeriodicMetricReaderModel().setExporter(metricExporter)))))
        .setLoggerProvider(
            new LoggerProviderModel()
                .setProcessors(
                    Collections.singletonList(
                        new LogRecordProcessorModel()
                            .setBatch(
                                new BatchLogRecordProcessorModel()
                                    .setExporter(logRecordExporter)))));
  }

  private static SpanExporterModel spanExporterOf(OpenTelemetryConfigurationModel model) {
    return model.getTracerProvider().getProcessors().get(0).getBatch().getExporter();
  }

  private static PushMetricExporterModel metricExporterOf(OpenTelemetryConfigurationModel model) {
    return model.getMeterProvider().getReaders().get(0).getPeriodic().getExporter();
  }

  private static LogRecordExporterModel logRecordExporterOf(OpenTelemetryConfigurationModel model) {
    return model.getLoggerProvider().getProcessors().get(0).getBatch().getExporter();
  }
}
