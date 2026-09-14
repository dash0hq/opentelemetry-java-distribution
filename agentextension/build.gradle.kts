// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.java-conventions")
  id("com.gradleup.shadow")
}

base.archivesName.set("dash0-opentelemetry-agentextension")

description = "Dash0 customisations as a standalone extension jar for the stock upstream agent"

// Delivery shape two: the same :custom module packaged as an extension, loaded
// by the unmodified upstream agent via OTEL_JAVAAGENT_EXTENSIONS. This keeps the
// stock agent jar shipping unchanged, so upstream upgrades stay a version bump.
//
// Deliberately no package relocation here: an extension jar is loaded by the
// agent into the agent class loader, where the shaded names already resolve.
// Relocating a second time would break it.
val shadowDependencies: Configuration by configurations.creating

dependencies {
  shadowDependencies(project(":custom"))
}

tasks {
  jar {
    enabled = false
    dependsOn(shadowJar)
  }

  shadowJar {
    configurations = listOf(shadowDependencies)
    archiveClassifier.set("")
    mergeServiceFiles()
  }

  assemble {
    dependsOn(shadowJar)
  }
}
