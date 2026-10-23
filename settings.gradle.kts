// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

// The toolchain below is declared as exactly Java 21, which Gradle cannot
// satisfy on a machine where no JDK 21 is installed, however new the JDK it is
// running on. This resolver lets it provision one, so the prerequisite in
// CONTRIBUTING.rst ("a JDK 21 or later") is true rather than aspirational.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "opentelemetry-java-distribution"

// The module names are deliberately the same as upstream's examples/distro
// (https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/examples/distro).
// Every vendor distribution uses them, which keeps upstream's build recipe
// copy-pasteable and keeps both delivery shapes open. See CLAUDE.md.
include("bootstrap")
include("custom")
include("agent")
include("agentextension")
include("smoke-tests")
