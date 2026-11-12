// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

import org.gradle.process.CommandLineArgumentProvider

plugins {
  id("dash0.java-conventions")
}

description = "Checks the assembled artifacts, not the source that went into them"

val agentJar: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}
val extensionJar: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

// The stock upstream agent, needed by two tests that cannot use the Dash0 one:
// the extension has to be loaded by an unmodified agent to prove that delivery
// shape works, and the jar-composition check has to diff against the jar this
// build repackaged.
val upstreamAgentJar: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
  isTransitive = false
}

dependencies {
  agentJar(project(path = ":agent", configuration = "shadow"))
  extensionJar(project(path = ":agentextension", configuration = "shadow"))
  upstreamAgentJar(libs.opentelemetryJavaagent)

  implementation(libs.slf4jApi)
  runtimeOnly(libs.logbackClassic)
  implementation(libs.log4jApi)
  runtimeOnly(libs.log4jCore)
  implementation(libs.jbossLogmanager)
}

tasks.withType<Test>().configureEach {
  // Set on purpose, and never read by anything here. ProcessBuilder.environment()
  // starts from the environment this JVM was launched with, so every JVM the tests
  // start inherits whatever the developer exported. Anyone working on OpenTelemetry
  // exports exactly these, and the suite then fails on a correct tree with nothing
  // to explain it. Making the hostile case the normal one means InstrumentedJvm has
  // to keep stripping them, and theLaunchedJvmIsNotReachedByTheAmbientEnvironment in
  // Dash0ConfigurationTest says so out loud.
  environment("OTEL_EXPORTER_OTLP_ENDPOINT", "http://ambient-environment.invalid:4318")
  environment("DASH0_DISABLE", "true")

  val resolvedAgentJar = agentJar
  val resolvedExtensionJar = extensionJar
  val resolvedUpstreamAgentJar = upstreamAgentJar
  val testApplicationClasspath = sourceSets.main.get().runtimeClasspath

  inputs.files(
    resolvedAgentJar,
    resolvedExtensionJar,
    resolvedUpstreamAgentJar,
    testApplicationClasspath,
  )

  // Resolved through an argument provider so the jars are built before the tests
  // run, rather than being resolved while the build is still being configured.
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Ddash0.agent.jar=${resolvedAgentJar.singleFile.absolutePath}",
        "-Ddash0.extension.jar=${resolvedExtensionJar.singleFile.absolutePath}",
        "-Ddash0.upstream.agent.jar=${resolvedUpstreamAgentJar.singleFile.absolutePath}",
        "-Ddash0.test.app.classpath=${testApplicationClasspath.asPath}",
        "-Ddash0.distro.version=$version",
      )
    },
  )
}
