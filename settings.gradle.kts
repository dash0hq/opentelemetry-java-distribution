// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
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
