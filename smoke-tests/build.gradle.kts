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
}

tasks.withType<Test>().configureEach {
  val resolvedAgentJar = agentJar
  val resolvedExtensionJar = extensionJar
  val resolvedUpstreamAgentJar = upstreamAgentJar
  val testApplicationClasses = sourceSets.main.get().output.classesDirs

  inputs.files(
    resolvedAgentJar,
    resolvedExtensionJar,
    resolvedUpstreamAgentJar,
    testApplicationClasses,
  )

  // Resolved through an argument provider so the jars are built before the tests
  // run, rather than being resolved while the build is still being configured.
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Ddash0.agent.jar=${resolvedAgentJar.singleFile.absolutePath}",
        "-Ddash0.extension.jar=${resolvedExtensionJar.singleFile.absolutePath}",
        "-Ddash0.upstream.agent.jar=${resolvedUpstreamAgentJar.singleFile.absolutePath}",
        "-Ddash0.test.app.classes=${testApplicationClasses.asPath}",
        "-Ddash0.distro.version=$version",
      )
    },
  )
}
