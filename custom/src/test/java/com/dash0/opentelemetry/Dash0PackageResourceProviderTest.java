/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Dash0PackageResourceProviderTest {

  @TempDir Path directory;

  @Test
  void isRegisteredAsAResourceProviderService() {
    assertThat(ServiceLoader.load(ResourceProvider.class))
        .anyMatch(Dash0PackageResourceProvider.class::isInstance);
  }

  @Test
  void readsEveryPropertyAsAnAttribute() throws IOException {
    Path file =
        write("dash0.package.name=dash0-java-autoinstrumentation\ndash0.package.version=1.2.3\n");

    Resource resource = Dash0PackageResourceProvider.resourceFrom(file);

    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.name")))
        .isEqualTo("dash0-java-autoinstrumentation");
    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.version")))
        .isEqualTo("1.2.3");
  }

  @Test
  void yieldsNothingWhenTheFileDoesNotExist() {
    assertThat(
            Dash0PackageResourceProvider.resourceFrom(
                directory.resolve(Dash0PackageResourceProvider.PROPERTIES_FILE_NAME)))
        .isEqualTo(Resource.empty());
  }

  @Test
  void yieldsNothingWhenTheFileIsADirectory() throws IOException {
    Path subdirectory =
        Files.createDirectory(directory.resolve(Dash0PackageResourceProvider.PROPERTIES_FILE_NAME));

    assertThat(Dash0PackageResourceProvider.resourceFrom(subdirectory)).isEqualTo(Resource.empty());
  }

  @Test
  void yieldsNothingWhenTheFileIsEmpty() throws IOException {
    assertThat(Dash0PackageResourceProvider.resourceFrom(write(""))).isEqualTo(Resource.empty());
  }

  @Test
  void skipsAPropertyWithAnEmptyValue() throws IOException {
    Resource resource =
        Dash0PackageResourceProvider.resourceFrom(
            write("dash0.package.name=\ndash0.package.version=1.2.3\n"));

    assertThat(resource.getAttributes().asMap())
        .containsOnlyKeys(AttributeKey.stringKey("dash0.package.version"));
  }

  @Test
  void acceptsAValueContainingAnEqualsSignAndASpace() throws IOException {
    Resource resource =
        Dash0PackageResourceProvider.resourceFrom(write("dash0.package.version=1.2.3+deb a=b\n"));

    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.version")))
        .isEqualTo("1.2.3+deb a=b");
  }

  @Test
  void ignoresCommentsAndBlankLines() throws IOException {
    Resource resource =
        Dash0PackageResourceProvider.resourceFrom(
            write("# written at package build time\n\ndash0.package.version=1.2.3\n"));

    assertThat(resource.getAttributes().asMap())
        .containsOnlyKeys(AttributeKey.stringKey("dash0.package.version"));
  }

  private Path write(String content) throws IOException {
    Path file = directory.resolve(Dash0PackageResourceProvider.PROPERTIES_FILE_NAME);
    Files.write(file, content.getBytes("UTF-8"));
    return file;
  }
}
