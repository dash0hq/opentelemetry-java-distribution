/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Asserts that MDC entries reach an exported log record, and that the credential-shaped ones do
 * not.
 *
 * <p>The unit tests in {@code :custom} prove that the customizer returns the right property names
 * and the right patterns. They cannot prove that the agent reads those names, that an excluded
 * selector with no included selector captures anything at all, or that the glob patterns match the
 * keys they were written for. Each of those fails silently: the agent starts, the application logs,
 * and the log record carries no attributes.
 *
 * <p>There is one application per appender instrumentation the distribution configures, and each
 * one exercises a different upstream class.
 *
 * <p>The MDC values are asserted against the exported log record rather than against the logging
 * framework's own console line, whose pattern in {@code smoke-tests/src/main/resources} carries no
 * MDC conversion word.
 */
class MdcAttributeCaptureTest {

  private static final String LOGBACK_SELECTOR =
      "otel.instrumentation.logback-appender.experimental.";

  @Test
  void logbackCapturesAnOrdinaryMdcKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class));
  }

  @Test
  void log4jCapturesAnOrdinaryContextDataKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(Log4jMdcApplication.class));
  }

  @Test
  void jbossLogManagerCapturesAnOrdinaryMdcKeyAndNotACredentialShapedOne() throws Exception {
    assertOnlyTheOrdinaryKeyIsExported(
        InstrumentedJvm.runningTheApplication(JbossLogManagerMdcApplication.class)
            .withJvmArguments("-Djava.util.logging.manager=org.jboss.logmanager.LogManager"));
  }

  @Test
  void anExcludedSelectorSetOnTheWorkloadReplacesTheDefault() throws Exception {
    String output =
        exportingLogsToTheConsole(
                InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class))
            .withJvmArguments("-D" + LOGBACK_SELECTOR + "mdc-attributes.excluded=*")
            .run();

    assertThat(output)
        .as(
            "excluding every key is how an operator turns MDC capture off again, and the"
                + " distribution's own excluded list must not survive it. The JVM's output"
                + " was:%n%s",
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
  void theDeprecatedCaptureSettingStillDecidesWhatIsCaptured() throws Exception {
    String output =
        exportingLogsToTheConsole(
                InstrumentedJvm.runningTheApplication(LogbackMdcApplication.class))
            .withJvmArguments(
                "-D"
                    + LOGBACK_SELECTOR
                    + "capture-mdc-attributes="
                    + MdcEntries.UPPER_CASE_AUTHORIZATION_KEY)
            .run();

    assertThat(output)
        .as(
            "the allow list names a key the distribution's deny list would have suppressed, which"
                + " is what makes the two cases tell apart. Standing down exports %s and nothing"
                + " else; contributing the deny list anyway makes agent 2.33.1 ignore"
                + " capture-mdc-attributes and export %s instead. The JVM's output was:%n%s",
            MdcEntries.UPPER_CASE_AUTHORIZATION_KEY, MdcEntries.ORDINARY_KEY, output)
        .contains(MdcEntries.CREDENTIAL_VALUE)
        .doesNotContain(MdcEntries.ORDINARY_VALUE);
  }

  private static void assertOnlyTheOrdinaryKeyIsExported(InstrumentedJvm jvm) throws Exception {
    String output = exportingLogsToTheConsole(jvm).run();

    assertThat(output)
        .as(
            "the distribution defaults mdc-attributes.excluded and sets no included selector, which"
                + " captures every key that does not match. The JVM's output was:%n%s",
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
