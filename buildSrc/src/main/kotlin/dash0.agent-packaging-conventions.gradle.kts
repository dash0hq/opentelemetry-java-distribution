// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("dash0.java-conventions")
  id("com.gradleup.shadow")
}

val bootstrapLibs: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val javaagentLibs: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val upstreamAgent: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

fun isolateClasses(jars: Iterable<File>): CopySpec = copySpec {
  jars.forEach {
    from(zipTree(it)) {
      into("inst")
      rename("^(.*)\\.class\$", "\$1.classdata")
      rename("^LICENSE\$", "LICENSE.renamed")
      exclude("META-INF/INDEX.LIST")
      exclude("META-INF/*.DSA")
      exclude("META-INF/*.SF")
      exclude("META-INF/maven/**")
      exclude("META-INF/MANIFEST.MF")
    }
  }
}

tasks {
  jar {
    enabled = false
  }

  val relocateJavaagentLibs = register<ShadowJar>("relocateJavaagentLibs") {
    configurations = listOf(javaagentLibs)

    archiveFileName.set("javaagentLibs-relocated.jar")

    duplicatesStrategy = DuplicatesStrategy.FAIL
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude("**/module-info.class")
    relocatePackages(this)

    dependencies {
      exclude(dependency("io.opentelemetry:opentelemetry-api"))
      exclude(dependency("io.opentelemetry:opentelemetry-common"))
      exclude(dependency("io.opentelemetry:opentelemetry-context"))
      exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv"))
      exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv-incubating"))
      exclude(dependency("io.opentelemetry:opentelemetry-api-incubator"))
    }
  }

  val isolateJavaagentLibs = register<Copy>("isolateJavaagentLibs") {
    dependsOn(relocateJavaagentLibs)
    with(isolateClasses(relocateJavaagentLibs.get().outputs.files))
    into(layout.buildDirectory.dir("isolated/javaagentLibs"))
  }

  shadowJar {
    configurations = listOf(bootstrapLibs, upstreamAgent)

    dependsOn(isolateJavaagentLibs)
    from(isolateJavaagentLibs)

    archiveClassifier.set("")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles("inst/META-INF/services")
    filesMatching("inst/META-INF/services/**") {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    exclude("**/module-info.class")
    relocatePackages(this)

    from(rootProject.file("LICENSE")) {
      into("META-INF")
    }

    manifest {
      attributes["Main-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
      attributes["Agent-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
      attributes["Premain-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
      attributes["Can-Redefine-Classes"] = "true"
      attributes["Can-Retransform-Classes"] = "true"
      attributes["Implementation-Vendor"] = "Dash0 Inc."
      attributes["Implementation-Title"] = "dash0-opentelemetry-javaagent"
      attributes["Implementation-Version"] =
        "dash0-${project.version}-otel-${rootProject.extra["opentelemetryJavaagentVersion"]}"
    }
  }

  assemble {
    dependsOn(shadowJar)
  }
}
