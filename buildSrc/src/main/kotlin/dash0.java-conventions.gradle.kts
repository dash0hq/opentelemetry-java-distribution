// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  java
  id("com.diffplug.spotless")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

group = rootProject.group
version = rootProject.version

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

tasks.withType<JavaCompile>().configureEach {
  // The upstream agent supports Java 8 and later, so everything that can end up
  // inside it must compile to Java 8 bytecode.
  options.release.set(8)
  // Two lint categories are switched off, and only these two:
  //   -options    "source/target value 8 is obsolete", a property of the Java 8
  //               floor above rather than of any code in this repository.
  //   -processing "No processor claimed any of these annotations: ...AutoService".
  //               AutoService's processor returns false from process(), so javac
  //               reports the annotation as unclaimed even though it handled it
  //               and wrote META-INF/services correctly. The registration is
  //               asserted for real in DistroResourceProviderTest and in the
  //               packaging smoke tests, which is where it actually matters.
  options.compilerArgs.addAll(
    listOf("-Werror", "-Xlint:all", "-Xlint:-options", "-Xlint:-processing"),
  )
}

dependencies {
  // The alpha BOM is required for the javaagent extension APIs; it transitively
  // provides the non-alpha instrumentation BOM.
  implementation(platform(libs.opentelemetryInstrumentationBomAlpha))
  implementation(platform(libs.opentelemetryBom))

  testImplementation(platform(libs.junitBom))
  testImplementation(libs.junitJupiter)
  testImplementation(libs.assertj)
  testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("passed", "skipped", "failed")
  }
}

spotless {
  java {
    googleJavaFormat()
    target("src/**/*.java")
    licenseHeader(
      """
      /*
       * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
       * SPDX-License-Identifier: Apache-2.0
       */
      """.trimIndent() + "\n",
    )
  }
  kotlinGradle {
    target("*.gradle.kts")
  }
}
