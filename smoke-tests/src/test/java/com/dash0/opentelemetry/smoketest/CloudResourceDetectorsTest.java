/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no cloud resource detector contributes an attribute unless its group toggle is set.
 *
 * <p>The toggles and the detectors each one covers are documented in {@code
 * docs/resource-detection.md}.
 */
class CloudResourceDetectorsTest {

  private static final String SITE_NAME = "smoke-test-site";

  private static final String APPLICATION_NAME = "smoke-test-cf-app";

  @Test
  void contributesNoCloudAttributeWhileNoGroupToggleIsSet() throws Exception {
    String exported = exportingTheResourceToTheLog(cloudEnvironment());

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("cloud.provider=")
        .doesNotContain("cloudfoundry.app.name=")
        .doesNotContain("faas.name=")
        .doesNotContain("service.name=\"" + SITE_NAME + "\"");
  }

  @Test
  void contributesCloudFoundryAttributesOnceTheCloudFoundryToggleIsSet() throws Exception {
    Map<String, String> environment = cloudEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_CLOUDFOUNDRY_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloudfoundry.app.name=\"" + APPLICATION_NAME + "\"");
  }

  @Test
  void takesTheServiceNameFromTheAzureSiteNameOnceTheAzureToggleIsSet() throws Exception {
    Map<String, String> environment = cloudEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloud.provider=\"azure\"")
        .contains("service.name=\"" + SITE_NAME + "\"");
  }

  /**
   * An environment that the Azure App Service, Azure Functions, AWS Lambda and Cloud Foundry
   * detectors each recognise as their own platform.
   */
  private static Map<String, String> cloudEnvironment() {
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put("WEBSITE_SITE_NAME", SITE_NAME);
    environment.put(
        "VCAP_APPLICATION", "{\"application_name\":\"" + APPLICATION_NAME + "\",\"index\":\"0\"}");
    environment.put("AWS_LAMBDA_FUNCTION_NAME", "smoke-test-function");
    environment.put("AWS_LAMBDA_FUNCTION_VERSION", "$LATEST");
    return environment;
  }

  private static String exportingTheResourceToTheLog(Map<String, String> environment)
      throws Exception {
    return InstrumentedJvm.runningTheTestApplication()
        .withJvmArguments(
            "-Dotel.traces.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.metrics.exporter=logging",
            "-Dotel.metric.export.interval=500")
        .withEnvironment(environment)
        .exitingAfter(4000)
        .run();
  }
}
