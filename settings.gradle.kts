// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "opentelemetry-java-distribution"

include("bootstrap")
include("custom")
include("agent")
include("agentextension")
include("smoke-tests")
