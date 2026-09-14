// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("dash0.java-conventions")
  id("com.gradleup.shadow")
}

// How the repackaged agent jar is assembled.
//
// This follows upstream's examples/distro recipe. Grafana, Splunk and Elastic
// all ship essentially this same file, because deviating from it fails in ways
// that are silent rather than loud: instrumentation that never activates, or a
// resource provider that is never invoked, with no error surfaced anywhere.
// Do not "clean this up" without reading CLAUDE.md first.

// Libraries that go into the bootstrap class loader, visible to the application
// and to every other class loader.
val bootstrapLibs: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

// Libraries that go into the agent class loader, isolated from the application.
val javaagentLibs: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

// The upstream agent being extended.
val upstreamAgent: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

fun isolateClasses(jars: Iterable<File>): CopySpec = copySpec {
  jars.forEach {
    from(zipTree(it)) {
      // Keep the "inst" prefix short: it is prepended to a great many strings
      // held in memory at runtime.
      into("inst")
      // Renaming .class to .classdata is the isolation mechanism itself. A file
      // named Foo.classdata is invisible to ordinary JVM class loading, so the
      // agent's own class loader can load it deliberately with no risk that the
      // instrumented application picks it up by accident.
      rename("^(.*)\\.class\$", "\$1.classdata")
      // LICENSE clashes with a license/ directory on case-insensitive file systems.
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

  // Building the final javaagent jar is done in 3 steps.

  // 1. Relocate the distribution's own javaagent libraries.
  val relocateJavaagentLibs = register<ShadowJar>("relocateJavaagentLibs") {
    configurations = listOf(javaagentLibs)

    archiveFileName.set("javaagentLibs-relocated.jar")

    duplicatesStrategy = DuplicatesStrategy.FAIL
    mergeServiceFiles()
    // mergeServiceFiles requires that the duplicate strategy is set to include.
    filesMatching("META-INF/services/**") {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude("**/module-info.class")
    relocatePackages(this)

    // These already live in the bootstrap class loader, so they must never
    // appear under inst/. Shipping a second copy produces two versions of the
    // same class at runtime, and the wrong one wins.
    dependencies {
      exclude(dependency("io.opentelemetry:opentelemetry-api"))
      exclude(dependency("io.opentelemetry:opentelemetry-common"))
      exclude(dependency("io.opentelemetry:opentelemetry-context"))
      exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv"))
      exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv-incubating"))
      exclude(dependency("io.opentelemetry:opentelemetry-api-incubator"))
    }
  }

  // 2. Move those libraries into inst/.
  // This needs its own task rather than being folded into shadowJar below:
  // duplicatesStrategy on shadowJar is not applied to files added via
  // with(CopySpec), because each CopySpec carries its own strategy.
  val isolateJavaagentLibs = register<Copy>("isolateJavaagentLibs") {
    dependsOn(relocateJavaagentLibs)
    with(isolateClasses(relocateJavaagentLibs.get().outputs.files))
    into(layout.buildDirectory.dir("isolated/javaagentLibs"))
  }

  // 3. Merge the isolated libraries with the bootstrap libraries (relocated
  // here) and the upstream agent jar, dropping duplicates.
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

    manifest {
      // Premain-Class deliberately stays on upstream's entry point.
      //
      // Splunk, Elastic and Amazon all point this at a class of their own, but
      // Splunk's is a bare pass-through to OpenTelemetryAgent and Elastic's only
      // sets a logging default first. Overriding it changes the class name in a
      // stack trace and little else. Add a Dash0 entry point only when there is
      // something that genuinely must run before the upstream premain.
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
