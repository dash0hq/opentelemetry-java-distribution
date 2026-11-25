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
  environment("OTEL_EXPORTER_OTLP_ENDPOINT", "http://ambient-environment.invalid:4318")
  environment("DASH0_DISABLE", "true")
  environment("SPRING_APPLICATION_NAME", "ambient-spring-app")

  val resolvedAgentJar = agentJar
  val resolvedExtensionJar = extensionJar
  val resolvedUpstreamAgentJar = upstreamAgentJar
  val testApplicationClasspath = sourceSets.main.get().runtimeClasspath

  val awsXrayPropagatorVersion = libs.versions.opentelemetryAwsXrayPropagator.get()
  inputs.property("awsXrayPropagatorVersion", awsXrayPropagatorVersion)
  inputs.files(
    resolvedAgentJar,
    resolvedExtensionJar,
    resolvedUpstreamAgentJar,
    testApplicationClasspath,
  )

  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Ddash0.agent.jar=${resolvedAgentJar.singleFile.absolutePath}",
        "-Ddash0.extension.jar=${resolvedExtensionJar.singleFile.absolutePath}",
        "-Ddash0.upstream.agent.jar=${resolvedUpstreamAgentJar.singleFile.absolutePath}",
        "-Ddash0.test.app.classpath=${testApplicationClasspath.asPath}",
        "-Ddash0.absent.default.configuration.file=" +
          layout.buildDirectory.file("no-default-configuration-file.yaml").get().asFile.absolutePath,
        "-Ddash0.distro.version=$version",
        "-Ddash0.aws.xray.propagator.version=$awsXrayPropagatorVersion",
      )
    },
  )
}
