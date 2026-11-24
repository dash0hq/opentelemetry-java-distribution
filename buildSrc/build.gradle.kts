// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  `kotlin-dsl`
}

dependencies {
  implementation("com.gradleup.shadow:shadow-gradle-plugin:${libs.versions.shadow.get()}")
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")

  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
