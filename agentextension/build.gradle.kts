// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.java-conventions")
  id("com.gradleup.shadow")
}

base.archivesName.set("dash0-opentelemetry-agentextension")

description = "Dash0 customisations as a standalone extension jar for the stock upstream agent"

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
