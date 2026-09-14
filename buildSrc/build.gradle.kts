// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  `kotlin-dsl`
}

dependencies {
  implementation("com.gradleup.shadow:shadow-gradle-plugin:${libs.versions.shadow.get()}")
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")

  // Puts the generated version-catalog accessors on the classpath of the
  // precompiled script plugins in src/main/kotlin, so they can use `libs` too.
  // Without this, `the<LibrariesForLibs>()` does not resolve and the catalog
  // would have to be duplicated into a second source of truth.
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
