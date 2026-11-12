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
 * <p>The MDC values are asserted against the exported log record rather than against Logback's own
 * console line, whose pattern in {@code smoke-tests/src/main/resources/logback.xml} carries no
 * {@code %X}.
 */
class MdcAttributeCaptureTest {

  @Test
  void anOrdinaryMdcKeyBecomesAnAttributeAndACredentialShapedOneDoesNot() throws Exception {
    String output = loggingApplicationExportingLogsToTheConsole().run();

    assertThat(output)
        .as(
            "the distribution defaults mdc-attributes.excluded and sets no included selector, which"
                + " captures every key that does not match. The JVM's output was:%n%s",
            output)
        .contains(MdcLoggingApplication.ORDINARY_VALUE);
    assertThat(output)
        .as(
            "the deny list is twelve case-sensitive globs covering three spellings, so %s, %s and"
                + " %s are all excluded. A dead agent exports nothing and would satisfy this"
                + " assertion on its own, which is why the attribute above is asserted present in"
                + " the same run. The JVM's output was:%n%s",
            MdcLoggingApplication.LOWER_CASE_KEY,
            MdcLoggingApplication.CAMEL_CASE_KEY,
            MdcLoggingApplication.UPPER_CASE_KEY,
            output)
        .doesNotContain(MdcLoggingApplication.CREDENTIAL_VALUE);
  }

  @Test
  void anExcludedSelectorSetOnTheWorkloadReplacesTheDefault() throws Exception {
    String output =
        loggingApplicationExportingLogsToTheConsole()
            .withJvmArguments(
                "-Dotel.instrumentation.logback-appender.experimental.mdc-attributes.excluded=*")
            .run();

    assertThat(output)
        .as(
            "excluding every key is how an operator turns MDC capture off again, and the"
                + " distribution's own excluded list must not survive it. The JVM's output"
                + " was:%n%s",
            output)
        .doesNotContain(MdcLoggingApplication.ORDINARY_VALUE)
        .doesNotContain(MdcLoggingApplication.CREDENTIAL_VALUE);
    assertThat(output)
        .as(
            "without this the test above would also pass against a JVM that never logged. The"
                + " JVM's output was:%n%s",
            output)
        .contains(MdcLoggingApplication.MESSAGE);
  }

  @Test
  void theDeprecatedCaptureSettingStillDecidesWhatIsCaptured() throws Exception {
    String output =
        loggingApplicationExportingLogsToTheConsole()
            .withJvmArguments(
                "-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes="
                    + MdcLoggingApplication.ORDINARY_KEY)
            .run();

    assertThat(output)
        .as(
            "agent 2.33.1 ignores capture-mdc-attributes as soon as an included or excluded pattern"
                + " is configured, so a default excluded list that did not stand down here would"
                + " turn this operator's allow list of one key into everything not denied, and"
                + " export %s as well. The JVM's output was:%n%s",
            MdcLoggingApplication.UPPER_CASE_KEY, output)
        .contains(MdcLoggingApplication.ORDINARY_VALUE)
        .doesNotContain(MdcLoggingApplication.CREDENTIAL_VALUE);
  }

  private static InstrumentedJvm loggingApplicationExportingLogsToTheConsole() {
    return InstrumentedJvm.runningTheMdcLoggingApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=logging",
            "-Dotel.service.name=smoke-test");
  }
}
