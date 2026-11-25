/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Asserts that agent 2.33.1 reads the MDC selectors {@code docs/configuration.md} documents, and
 * that nothing is captured until one of them is set.
 *
 * <p>There is one application per appender instrumentation that reads an MDC selector, and each one
 * exercises a different upstream class.
 */
class MdcAttributeCaptureTest {

  private static final String LOGBACK_EXCLUDED =
      "OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED";
  private static final String LOG4J_EXCLUDED =
      "OTEL_INSTRUMENTATION_LOG4J_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED";
  private static final String JBOSS_LOGMANAGER_EXCLUDED =
      "OTEL_INSTRUMENTATION_JBOSS_LOGMANAGER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED";
  private static final String LOGBACK_INCLUDED =
      "OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_INCLUDED";

  @Test
  void logbackCapturesAnOrdinaryMdcKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class)
            .withEnvironmentVariable(LOGBACK_EXCLUDED, MdcEntries.CREDENTIAL_KEY_PATTERNS));
  }

  @Test
  void log4jCapturesAnOrdinaryContextDataKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(Log4jMdcApplication.class)
            .withEnvironmentVariable(LOG4J_EXCLUDED, MdcEntries.CREDENTIAL_KEY_PATTERNS));
  }

  @Test
  void jbossLogManagerCapturesAnOrdinaryMdcKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(JbossLogManagerMdcApplication.class)
            .withJvmArguments("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
            .withEnvironmentVariable(
                JBOSS_LOGMANAGER_EXCLUDED, MdcEntries.CREDENTIAL_KEY_PATTERNS));
  }

  @Test
  void nothingIsCapturedWhenTheWorkloadSetsNoSelector() throws Exception {
    String output =
        exportingLogsToTheConsole(
                InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class))
            .run();

    assertThat(output)
        .as(
            "capture is opt-in, so a workload that sets none of the three MDC properties exports"
                + " the record and none of its MDC entries. The JVM's output was:%n%s",
            output)
        .doesNotContain(MdcEntries.ORDINARY_VALUE)
        .doesNotContain(MdcEntries.CREDENTIAL_VALUE);
    assertThat(output)
        .as(
            "without this the assertion above would also pass against a JVM whose log pipeline went"
                + " silent, which is the failure the other cases exist to catch. Logback prints the"
                + " message to the console whether or not a record was exported, so the assertion"
                + " is on the exported record's own marker instead. The JVM's output was:%n%s",
            output)
        .contains(exportedRecordMarkerFor(LogbackMdcApplication.class));
  }

  @Test
  void anIncludedSelectorNamesTheKeysToCapture() throws Exception {
    String output =
        exportingLogsToTheConsole(
                InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class))
            .withEnvironmentVariable(LOGBACK_INCLUDED, MdcEntries.UPPER_CASE_AUTHORIZATION_KEY)
            .run();

    assertThat(output)
        .as(
            "an included selector captures the keys it names and nothing else, which is the second"
                + " of the two spellings docs/configuration.md gives an operator. The key it names"
                + " here is %s, and the ordinary key the deny list would have let through is"
                + " absent. The JVM's output was:%n%s",
            MdcEntries.UPPER_CASE_AUTHORIZATION_KEY, output)
        .contains(MdcEntries.CREDENTIAL_VALUE)
        .doesNotContain(MdcEntries.ORDINARY_VALUE);
  }

  @Test
  void theDeprecatedCaptureSettingNamesTheKeysToCapture() throws Exception {
    String output =
        exportingLogsToTheConsole(
                InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class))
            .withJvmArguments(
                "-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes="
                    + MdcEntries.UPPER_CASE_AUTHORIZATION_KEY)
            .run();

    assertThat(output)
        .as(
            "capture-mdc-attributes is the deprecated predecessor of the included and excluded"
                + " pair, and agent 2.33.1 still reads it as a list of literal keys. It names %s"
                + " here, so that key is exported and the ordinary key is not. The JVM's output"
                + " was:%n%s",
            MdcEntries.UPPER_CASE_AUTHORIZATION_KEY, output)
        .contains(MdcEntries.CREDENTIAL_VALUE)
        .doesNotContain(MdcEntries.ORDINARY_VALUE);
  }

  private static void assertOnlyTheOrdinaryKeyIsExported(InstrumentedJvm jvm) throws Exception {
    String output = exportingLogsToTheConsole(jvm).run();

    assertThat(output)
        .as(
            "an excluded selector with no included selector beside it captures every key that does"
                + " not match, so the deny list alone both turns capture on and keeps the"
                + " credential-shaped keys out. The JVM's output was:%n%s",
            output)
        .contains(MdcEntries.ORDINARY_VALUE);
    assertThat(output)
        .as(
            "the application sets one credential-shaped key per deny-list pattern, each matched by"
                + " that pattern alone, so deleting or mistyping any one of the twelve exports the"
                + " key it covered: %s. A dead agent exports nothing and would satisfy this"
                + " assertion on its own, which is why the attribute above is asserted present in"
                + " the same run. The JVM's output was:%n%s",
            MdcEntries.CREDENTIAL_KEYS, output)
        .doesNotContain(MdcEntries.CREDENTIAL_VALUE);
  }

  /** What the console log exporter prints for a record whose scope is named after {@code type}. */
  private static String exportedRecordMarkerFor(Class<?> type) {
    return "[scopeInfo: " + type.getName();
  }

  private static InstrumentedJvm exportingLogsToTheConsole(InstrumentedJvm jvm) {
    return jvm.withJvmArguments(
        "-Dotel.traces.exporter=none",
        "-Dotel.metrics.exporter=none",
        "-Dotel.logs.exporter=logging",
        "-Dotel.service.name=smoke-test");
  }
}
