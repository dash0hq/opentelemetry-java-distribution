/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry.smoketest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no detector upstream ships disabled contributes an attribute unless its group toggle
 * is set.
 *
 * <p>The toggles and the detectors each one covers are documented in {@code
 * docs/resource-detection.md}.
 */
class CloudResourceDetectorsTest {

  private static final String SITE_NAME = "smoke-test-site";

  private static final String APPLICATION_NAME = "smoke-test-cf-app";

  private static final String FUNCTIONS_VERSION = "~4";

  private static final String SPRING_APPLICATION_NAME = "smoke-test-spring-app";

  private static final String SERVICE_NAME = "smoke-test-service";

  @Test
  void contributesNoCloudAttributeWhileNoGroupToggleIsSet() throws Exception {
    String exported = exportingTheResourceToTheLog(appServiceEnvironment());

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("cloud.provider=")
        .doesNotContain("cloudfoundry.app.name=")
        .doesNotContain("faas.name=")
        .doesNotContain("host.id=")
        .doesNotContain("service.name=\"" + SITE_NAME + "\"")
        .contains("service.name=\"" + SPRING_APPLICATION_NAME + "\"");
  }

  @Test
  void contributesNoAzureFunctionsAttributeWhileNoGroupToggleIsSet() throws Exception {
    String exported = exportingTheResourceToTheLog(functionsEnvironment());

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .doesNotContain("cloud.provider=")
        .doesNotContain("faas.name=")
        .doesNotContain("service.name=\"" + SITE_NAME + "\"")
        .contains("service.name=\"" + SPRING_APPLICATION_NAME + "\"");
  }

  @Test
  void contributesCloudFoundryAttributesOnceTheCloudFoundryToggleIsSet() throws Exception {
    Map<String, String> environment = appServiceEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_CLOUDFOUNDRY_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloudfoundry.app.name=\"" + APPLICATION_NAME + "\"");
  }

  @Test
  void contributesTheHostIdOnceTheHostIdToggleIsSet() throws Exception {
    assumeTrue(
        aMachineIdFileOfThisHostCarriesAValue(),
        "every machine id file HostIdResource reads is missing or empty on this host");

    Map<String, String> environment = appServiceEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_HOST_ID_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("host.id=");
  }

  @Test
  void takesTheServiceNameFromTheAzureSiteNameOnceTheAzureToggleIsSet() throws Exception {
    Map<String, String> environment = appServiceEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloud.provider=\"azure\"")
        .contains("cloud.platform=\"azure.app_service\"")
        .contains("service.name=\"" + SITE_NAME + "\"");
  }

  @Test
  void takesTheServiceNameFromTheAzureSiteNameOnAzureFunctionsTooOnceTheAzureToggleIsSet()
      throws Exception {
    Map<String, String> environment = functionsEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED", "true");

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloud.provider=\"azure\"")
        .contains("cloud.platform=\"azure.functions\"")
        .contains("faas.name=\"" + SITE_NAME + "\"")
        .contains("service.name=\"" + SITE_NAME + "\"");
  }

  @Test
  void letsTheServiceNameVariableBeatTheAzureSiteName() throws Exception {
    Map<String, String> environment = appServiceEnvironment();
    environment.put("OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED", "true");
    environment.put("OTEL_SERVICE_NAME", SERVICE_NAME);

    String exported = exportingTheResourceToTheLog(environment);

    assertThat(exported)
        .as("exported resource attributes were:%n%s", exported)
        .contains("telemetry.distro.name=\"dash0-java\"")
        .contains("cloud.platform=\"azure.app_service\"")
        .contains("service.name=\"" + SERVICE_NAME + "\"")
        .doesNotContain("service.name=\"" + SITE_NAME + "\"");
  }

  /**
   * Whether either path {@code HostIdResource} reads on Linux holds a non-blank line, which is what
   * it turns into {@code host.id}.
   */
  private static boolean aMachineIdFileOfThisHostCarriesAValue() throws IOException {
    List<String> machineIdPaths = Arrays.asList("/etc/machine-id", "/var/lib/dbus/machine-id");
    for (String machineIdPath : machineIdPaths) {
      Path file = Paths.get(machineIdPath);
      if (!Files.isReadable(file)) {
        continue;
      }
      for (String line : Files.readAllLines(file)) {
        if (!line.trim().isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * An environment that the Azure App Service, AWS Lambda and Cloud Foundry detectors each
   * recognise as their own platform, and that gives {@code SpringBootServiceNameDetector} a name of
   * its own to contribute.
   */
  private static Map<String, String> appServiceEnvironment() {
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put("SPRING_APPLICATION_NAME", SPRING_APPLICATION_NAME);
    environment.put("WEBSITE_SITE_NAME", SITE_NAME);
    environment.put(
        "VCAP_APPLICATION", "{\"application_name\":\"" + APPLICATION_NAME + "\",\"index\":\"0\"}");
    environment.put("AWS_LAMBDA_FUNCTION_NAME", "smoke-test-function");
    environment.put("AWS_LAMBDA_FUNCTION_VERSION", "$LATEST");
    return environment;
  }

  /**
   * The same environment with {@code FUNCTIONS_EXTENSION_VERSION} added, which is what {@code
   * AzureEnvVarPlatform.detect} answers {@code FUNCTIONS} to instead of {@code APP_SERVICE}.
   */
  private static Map<String, String> functionsEnvironment() {
    Map<String, String> environment = appServiceEnvironment();
    environment.put("FUNCTIONS_EXTENSION_VERSION", FUNCTIONS_VERSION);
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
