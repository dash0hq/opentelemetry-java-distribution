// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.java-conventions")
}

// All Dash0-authored SDK customisation lives here, and only here.
//
// This module is consumed unchanged by both :agent (the repackaged agent jar)
// and :agentextension (the standalone extension jar). That is what keeps the
// delivery choice cheap to revisit: dropping or swapping a shape moves no code.
// Elastic ships both artifacts from one module this way.
//
// Dependencies are compileOnly: the agent supplies all of these at runtime, and
// bundling a second copy is the mistake that breaks the class loader isolation.
dependencies {
  compileOnly(project(":bootstrap"))
  compileOnly(libs.opentelemetrySdk)
  compileOnly(libs.opentelemetrySdkAutoconfigureSpi)
  compileOnly(libs.opentelemetryJavaagentExtensionApi)
  compileOnly(libs.opentelemetryInstrumentationApi)

  compileOnly(libs.autoServiceAnnotations)
  annotationProcessor(libs.autoServiceProcessor)

  testImplementation(libs.opentelemetrySdk)
  testImplementation(libs.opentelemetrySdkAutoconfigureSpi)
  testImplementation(libs.opentelemetrySdkTesting)
  // The tests read DistroResourceProvider, which carries @AutoService, so the
  // annotation has to be resolvable when compiling them even though it is
  // compileOnly for the main source set.
  testCompileOnly(libs.autoServiceAnnotations)
}

// The distribution version is reported as telemetry.distro.version, so it has to
// reach runtime as data rather than as a compiled-in constant that drifts.
val generateVersionResource = tasks.register<WriteProperties>("generateVersionResource") {
  destinationFile.set(layout.buildDirectory.file("generated/version/dash0-distro-version.properties"))
  property("version", project.version.toString())
}

sourceSets {
  main {
    output.dir(generateVersionResource.map { it.destinationFile.get().asFile.parentFile })
  }
}

tasks.named<ProcessResources>("processResources") {
  dependsOn(generateVersionResource)
}
