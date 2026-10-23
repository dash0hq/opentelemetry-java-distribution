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

    from(rootProject.file("LICENSE")) {
      into("META-INF")
    }

    // Without these the manifest is the bare "Manifest-Version: 1.0" that
    // shadow writes by default, and a published release asset has no way to
    // report what it is or which upstream agent it was built against other
    // than its own file name. The agent jar declares the same three.
    manifest {
      attributes["Implementation-Vendor"] = "Dash0 Inc."
      attributes["Implementation-Title"] = "dash0-opentelemetry-agentextension"
      attributes["Implementation-Version"] =
        "dash0-${project.version}-otel-${rootProject.extra["opentelemetryJavaagentVersion"]}"
    }
  }

  assemble {
    dependsOn(shadowJar)
  }
}
