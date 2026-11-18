/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Dash0PackageResourceTest {

  @TempDir Path directory;

  @Test
  void readsEveryPropertyAsAnAttribute() throws IOException {
    Path file =
        write("dash0.package.name=dash0-java-autoinstrumentation\ndash0.package.version=1.2.3\n");

    Resource resource = Dash0PackageResource.resourceFrom(file);

    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.name")))
        .isEqualTo("dash0-java-autoinstrumentation");
    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.version")))
        .isEqualTo("1.2.3");
  }

  @Test
  void yieldsNothingWhenTheFileDoesNotExist() {
    assertThat(
            Dash0PackageResource.resourceFrom(
                directory.resolve(Dash0PackageResource.PROPERTIES_FILE_NAME)))
        .isEqualTo(Resource.empty());
  }

  @Test
  void yieldsNothingWhenTheFileIsADirectory() throws IOException {
    Path subdirectory =
        Files.createDirectory(directory.resolve(Dash0PackageResource.PROPERTIES_FILE_NAME));

    assertThat(Dash0PackageResource.resourceFrom(subdirectory)).isEqualTo(Resource.empty());
  }

  @Test
  void yieldsNothingWhenTheFileIsEmpty() throws IOException {
    assertThat(Dash0PackageResource.resourceFrom(write(""))).isEqualTo(Resource.empty());
  }

  @Test
  void skipsAPropertyWithAnEmptyValue() throws IOException {
    Resource resource =
        Dash0PackageResource.resourceFrom(
            write("dash0.package.name=\ndash0.package.version=1.2.3\n"));

    assertThat(resource.getAttributes().asMap())
        .containsOnlyKeys(AttributeKey.stringKey("dash0.package.version"));
  }

  @Test
  void acceptsAValueContainingAnEqualsSignAndASpace() throws IOException {
    Resource resource =
        Dash0PackageResource.resourceFrom(write("dash0.package.version=1.2.3+deb a=b\n"));

    assertThat(resource.getAttribute(AttributeKey.stringKey("dash0.package.version")))
        .isEqualTo("1.2.3+deb a=b");
  }

  @Test
  void ignoresCommentsAndBlankLines() throws IOException {
    Resource resource =
        Dash0PackageResource.resourceFrom(
            write("# written at package build time\n\ndash0.package.version=1.2.3\n"));

    assertThat(resource.getAttributes().asMap())
        .containsOnlyKeys(AttributeKey.stringKey("dash0.package.version"));
  }

  @Test
  void yieldsNothingWhenNoFileSitsBesideTheClassesOnTheTestClasspath() {
    assertThat(Dash0PackageResource.get()).isEqualTo(Resource.empty());
  }

  private Path write(String content) throws IOException {
    Path file = directory.resolve(Dash0PackageResource.PROPERTIES_FILE_NAME);
    Files.write(file, content.getBytes("UTF-8"));
    return file;
  }
}
