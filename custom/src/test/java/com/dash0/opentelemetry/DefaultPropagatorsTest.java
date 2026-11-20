/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Resolves the propagators through SDK autoconfiguration, with this distribution's customizer and
 * the X-Ray propagator found the way the agent finds them, and checks what an AWS load balancer
 * between two services does to the trace.
 */
class DefaultPropagatorsTest {

  private static final String TRACEPARENT = "traceparent";
  private static final String XRAY = "X-Amzn-Trace-Id";

  private static final String W3C_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String W3C_SPAN_ID = "b7ad6b7169203331";
  private static final String W3C_TRACEPARENT = "00-" + W3C_TRACE_ID + "-" + W3C_SPAN_ID + "-01";

  private static final String XRAY_TRACE_ID = "5759e988bd862e3fe1be46a994272793";
  private static final String XRAY_SPAN_ID = "53995c3f42cd8ad8";
  private static final String XRAY_HEADER =
      "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1";

  private OpenTelemetrySdk sdk;

  @AfterEach
  void closeSdk() {
    System.clearProperty("otel.propagators");
    if (sdk != null) {
      sdk.close();
    }
  }

  @Test
  void injectsTracecontextBaggageAndXrayByDefault() {
    Map<String, String> headers = inject(defaultPropagator(), sampledSpan(), "k", "v");

    assertThat(headers.keySet())
        .as("injection order follows OTEL_PROPAGATORS")
        .containsExactly(TRACEPARENT, "baggage", XRAY);
  }

  @Test
  void injectsAnXrayHeaderWithAParentSoADownstreamLoadBalancerKeepsTheTrace() {
    Map<String, String> headers = inject(defaultPropagator(), sampledSpan(), null, null);

    assertThat(headers)
        .containsEntry(TRACEPARENT, W3C_TRACEPARENT)
        .containsEntry(
            XRAY, "Root=1-0af76519-16cd43dd8448eb211c80319c;Parent=" + W3C_SPAN_ID + ";Sampled=1");
  }

  @Test
  void prefersTheXrayContextWhenBothHeadersArePresent() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACEPARENT, W3C_TRACEPARENT);
    headers.put(XRAY, XRAY_HEADER);

    SpanContext extracted = extractSpanContext(defaultPropagator(), headers);

    assertThat(extracted.getTraceId()).isEqualTo(XRAY_TRACE_ID);
    assertThat(extracted.getSpanId()).isEqualTo(XRAY_SPAN_ID);
  }

  @Test
  void acceptsAHeaderALoadBalancerRewroteWithASelfField() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACEPARENT, W3C_TRACEPARENT);
    headers.put(
        XRAY, "Self=1-67891234-12456789abcdef0123456789;" + XRAY_HEADER + ";CalledFrom=Foo");

    SpanContext extracted = extractSpanContext(defaultPropagator(), headers);

    assertThat(extracted.isValid()).isTrue();
    assertThat(extracted.getTraceId()).isEqualTo(XRAY_TRACE_ID);
    assertThat(extracted.getSpanId()).isEqualTo(XRAY_SPAN_ID);
  }

  @Test
  void keepsTheTracecontextParentWhenALoadBalancerAddedARootWithoutAParent() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACEPARENT, W3C_TRACEPARENT);
    headers.put(XRAY, "Root=1-67891233-abcdef012345678912345678");

    SpanContext extracted = extractSpanContext(defaultPropagator(), headers);

    assertThat(extracted.isValid()).isTrue();
    assertThat(extracted.getTraceId())
        .as(
            "a load balancer adds a Root with no Parent when the request arrived without an X-Ray"
                + " header; replacing the traceparent context with it would split the trace")
        .isEqualTo(W3C_TRACE_ID);
    assertThat(extracted.getSpanId()).isEqualTo(W3C_SPAN_ID);
  }

  @Test
  void extractsNoParentFromARootWithoutAParentAlone() {
    SpanContext extracted =
        extractSpanContext(
            defaultPropagator(), singleHeader(XRAY, "Root=1-67891233-abcdef012345678912345678"));

    assertThat(extracted.isValid()).isFalse();
  }

  @Test
  void roundTripsBaggageByDefault() {
    Map<String, String> incoming = new HashMap<>();
    incoming.put(TRACEPARENT, W3C_TRACEPARENT);
    incoming.put("baggage", "tenant=acme");
    incoming.put(XRAY, XRAY_HEADER);

    TextMapPropagator propagator = defaultPropagator();
    Context extracted = propagator.extract(Context.root(), incoming, MapGetter.INSTANCE);
    Map<String, String> outgoing = new LinkedHashMap<>();
    propagator.inject(extracted, outgoing, Map::put);

    assertThat(Baggage.fromContext(extracted).getEntryValue("tenant")).isEqualTo("acme");
    assertThat(outgoing).containsEntry("baggage", "tenant=acme");
  }

  @Test
  void keepsBothTheBaggageEntriesAndTheXrayLineage() {
    Map<String, String> incoming = new HashMap<>();
    incoming.put("baggage", "tenant=acme");
    incoming.put(XRAY, XRAY_HEADER + ";Lineage=2:fe3d4a5b:1");

    Baggage baggage =
        Baggage.fromContext(
            defaultPropagator().extract(Context.root(), incoming, MapGetter.INSTANCE));

    assertThat(baggage.getEntryValue("tenant")).isEqualTo("acme");
    assertThat(baggage.getEntryValue("Lineage")).isEqualTo("2:fe3d4a5b:1");
  }

  @Test
  void honoursAWorkloadsOwnPropagators() {
    System.setProperty("otel.propagators", "tracecontext");
    TextMapPropagator propagator = defaultPropagator();

    Map<String, String> incoming = new HashMap<>();
    incoming.put(TRACEPARENT, W3C_TRACEPARENT);
    incoming.put(XRAY, XRAY_HEADER);

    assertThat(extractSpanContext(propagator, incoming).getTraceId()).isEqualTo(W3C_TRACE_ID);
    assertThat(inject(propagator, sampledSpan(), "k", "v").keySet()).containsExactly(TRACEPARENT);
  }

  private TextMapPropagator defaultPropagator() {
    Map<String, String> quiet = new HashMap<>();
    quiet.put("otel.traces.exporter", "none");
    quiet.put("otel.metrics.exporter", "none");
    quiet.put("otel.logs.exporter", "none");
    sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> quiet)
            .build()
            .getOpenTelemetrySdk();
    return sdk.getPropagators().getTextMapPropagator();
  }

  private static Context sampledSpan() {
    return Context.root()
        .with(
            Span.wrap(
                SpanContext.create(
                    W3C_TRACE_ID, W3C_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())));
  }

  private static Map<String, String> inject(
      TextMapPropagator propagator, Context context, String baggageKey, String baggageValue) {
    if (baggageKey != null) {
      context = context.with(Baggage.builder().put(baggageKey, baggageValue).build());
    }
    Map<String, String> headers = new LinkedHashMap<>();
    propagator.inject(context, headers, Map::put);
    return headers;
  }

  private static SpanContext extractSpanContext(
      TextMapPropagator propagator, Map<String, String> headers) {
    return Span.fromContext(propagator.extract(Context.root(), headers, MapGetter.INSTANCE))
        .getSpanContext();
  }

  private static Map<String, String> singleHeader(String name, String value) {
    Map<String, String> headers = new HashMap<>();
    headers.put(name, value);
    return headers;
  }

  private enum MapGetter implements TextMapGetter<Map<String, String>> {
    INSTANCE;

    @Override
    public String get(Map<String, String> carrier, String key) {
      return carrier == null ? null : carrier.get(key);
    }
  }
}
