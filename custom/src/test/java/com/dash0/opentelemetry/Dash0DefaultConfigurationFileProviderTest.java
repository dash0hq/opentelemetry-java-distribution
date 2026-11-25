/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static com.dash0.opentelemetry.Dash0DefaultConfigurationFileProvider.DEFAULT_CONFIGURATION_FILE;
import static com.dash0.opentelemetry.Dash0DefaultConfigurationFileProvider.DEFAULT_CONFIGURATION_FILE_PROPERTY;
import static com.dash0.opentelemetry.Dash0DefaultConfigurationFileProvider.parseTheDefaultConfigurationFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.incubator.config.DeclarativeConfigException;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AttributeNameValueModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class Dash0DefaultConfigurationFileProviderTest {

  private static final String A_FILE_THAT_NAMES_A_SERVICE =
      "file_format: \"1.0\"\n"
          + "resource:\n"
          + "  attributes:\n"
          + "    - name: service.name\n"
          + "      value: from-the-default-file\n";

  @Test
  void isRegisteredAsADeclarativeConfigurationProviderService() {
    assertThat(ServiceLoader.load(DeclarativeConfigurationProvider.class))
        .as("the SDK reaches this class through no other route")
        .anyMatch(Dash0DefaultConfigurationFileProvider.class::isInstance);
  }

  @Test
  void readsThePathTheDash0LinuxPackagesInstallTheFileAt() {
    assertThat(DEFAULT_CONFIGURATION_FILE).isEqualTo("/etc/opentelemetry/java/otel-config.yaml");
  }

  @Test
  void readsTheFileTheDefaultConfigurationFilePropertyNames(@TempDir Path directory)
      throws IOException {
    OpenTelemetryConfigurationModel model =
        modelFromTheProviderReading(fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE));

    assertThat(serviceNameIn(model))
        .as(
            "the build points every test JVM at a path under build/ that nothing creates, so a"
                + " host that has the Dash0 Java package installed runs the same tests")
        .isEqualTo("from-the-default-file");
  }

  @Test
  void standsAsideWhenTheDefaultConfigurationFilePropertyNamesNoFile(@TempDir Path directory) {
    assertThat(modelFromTheProviderReading(directory.resolve("otel-config.yaml"))).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void parsesTheDefaultFileWhenOtelConfigFileIsUnsetOrBlank(
      String configuredConfigurationFile, @TempDir Path directory) throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE);

    OpenTelemetryConfigurationModel model =
        parseTheDefaultConfigurationFile(
            configuredConfigurationFile, null, defaultConfigurationFile);

    assertThat(serviceNameIn(model)).isEqualTo("from-the-default-file");
  }

  @Test
  void leavesTheFileOtelConfigFileNamesToTheSdk(@TempDir Path directory) throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE);
    Path namedByTheOperator = directory.resolve("named-by-the-operator.yaml");
    Files.write(namedByTheOperator, A_FILE_THAT_NAMES_A_SERVICE.getBytes(StandardCharsets.UTF_8));

    OpenTelemetryConfigurationModel model =
        parseTheDefaultConfigurationFile(
            namedByTheOperator.toString(), null, defaultConfigurationFile);

    assertThat(model)
        .as(
            "the SDK consults this SPI before it reads otel.config.file, so a provider that"
                + " answered here would silently replace the file the operator named")
        .isNull();
  }

  @Test
  void leavesAnOtelConfigFileThatNamesNoExistingFileToTheSdk(@TempDir Path directory)
      throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE);

    OpenTelemetryConfigurationModel model =
        parseTheDefaultConfigurationFile(
            directory.resolve("does-not-exist.yaml").toString(), null, defaultConfigurationFile);

    assertThat(model)
        .as(
            "an explicit setting that names a missing file is a mistake the SDK reports, and"
                + " falling back to another file would hide it")
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "True"})
  void standsAsideWhenOtelSdkDisabledIsTrue(String sdkDisabled, @TempDir Path directory)
      throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE);

    assertThat(parseTheDefaultConfigurationFile(null, sdkDisabled, defaultConfigurationFile))
        .as(
            "a declaratively configured SDK reads no OTEL_SDK_DISABLED, so a host-wide default"
                + " file that loaded anyway would restart a workload an operator silenced")
        .isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "false", "1", "yes", " true "})
  void parsesTheDefaultFileForEveryOtelSdkDisabledTheSdkReadsAsFalse(
      String sdkDisabled, @TempDir Path directory) throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, A_FILE_THAT_NAMES_A_SERVICE);

    assertThat(
            serviceNameIn(
                parseTheDefaultConfigurationFile(null, sdkDisabled, defaultConfigurationFile)))
        .as(
            "the comparison is Boolean.parseBoolean on the raw value, which is what"
                + " DefaultConfigProperties.getBoolean does, so the file loads for exactly the"
                + " values that leave the SDK enabled under environment configuration")
        .isEqualTo("from-the-default-file");
  }

  @Test
  void standsAsideWhenTheDefaultFileDoesNotExist(@TempDir Path directory) {
    assertThat(parseTheDefaultConfigurationFile(null, null, directory.resolve("otel-config.yaml")))
        .isNull();
  }

  @Test
  void standsAsideWhenTheDefaultPathIsADirectory(@TempDir Path directory) throws IOException {
    Path defaultConfigurationFile = directory.resolve("otel-config.yaml");
    Files.createDirectory(defaultConfigurationFile);

    assertThat(parseTheDefaultConfigurationFile(null, null, defaultConfigurationFile)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   \n", "# nothing to see here\n", "---\n"})
  void failsWhenTheDefaultFileHoldsNoConfiguration(String content, @TempDir Path directory)
      throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, content);

    assertThatThrownBy(() -> parseTheDefaultConfigurationFile(null, null, defaultConfigurationFile))
        .as(
            "DeclarativeConfiguration.parse answers null for a file that holds no document, which"
                + " a truncated write and a template that rendered nothing both produce, and the"
                + " Python and Node.js distributions surface that instead of degrading to the"
                + " environment")
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("holds no OpenTelemetry configuration");
  }

  @Test
  void failsWhenTheDefaultFileCannotBeParsed(@TempDir Path directory) throws IOException {
    Path defaultConfigurationFile = fileHolding(directory, "file_format: [\"1.0\"\n");

    assertThatThrownBy(() -> parseTheDefaultConfigurationFile(null, null, defaultConfigurationFile))
        .as(
            "a broken file aborts autoconfiguration and the JVM then runs uninstrumented, which is"
                + " what the Python and Node.js distributions do with the same file")
        .isInstanceOf(DeclarativeConfigException.class);
  }

  @Test
  void substitutesAnEnvironmentReferenceTheDefaultFileMakes(@TempDir Path directory)
      throws IOException {
    Path defaultConfigurationFile =
        fileHolding(
            directory,
            "file_format: \"1.0\"\n"
                + "resource:\n"
                + "  attributes:\n"
                + "    - name: service.name\n"
                + "      value: ${A_VARIABLE_NOTHING_SETS:-from-the-fallback}\n");

    OpenTelemetryConfigurationModel model =
        parseTheDefaultConfigurationFile(null, null, defaultConfigurationFile);

    assertThat(serviceNameIn(model))
        .as(
            "the default file goes through the same DeclarativeConfiguration.parse as the file"
                + " OTEL_CONFIG_FILE names, so it gets the same environment substitution")
        .isEqualTo("from-the-fallback");
  }

  private static OpenTelemetryConfigurationModel modelFromTheProviderReading(
      Path defaultConfigurationFile) {
    String previous = System.getProperty(DEFAULT_CONFIGURATION_FILE_PROPERTY);
    System.setProperty(DEFAULT_CONFIGURATION_FILE_PROPERTY, defaultConfigurationFile.toString());
    try {
      return new Dash0DefaultConfigurationFileProvider().getConfigurationModel();
    } finally {
      if (previous == null) {
        System.clearProperty(DEFAULT_CONFIGURATION_FILE_PROPERTY);
      } else {
        System.setProperty(DEFAULT_CONFIGURATION_FILE_PROPERTY, previous);
      }
    }
  }

  private static Path fileHolding(Path directory, String content) throws IOException {
    Path file = directory.resolve("otel-config.yaml");
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static Object serviceNameIn(OpenTelemetryConfigurationModel model) {
    assertThat(model).isNotNull();
    assertThat(model.getResource()).isNotNull();
    for (AttributeNameValueModel attribute : model.getResource().getAttributes()) {
      if ("service.name".equals(attribute.getName())) {
        return attribute.getValue();
      }
    }
    return null;
  }
}
