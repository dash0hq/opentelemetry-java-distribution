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
  options.release.set(8)
  // Two lint categories are switched off, and only these two:
  //   -options    "source/target value 8 is obsolete", a property of the Java 8 floor.
  //   -processing AutoService's processor returns false from process(), so javac reports
  //               its annotation as unclaimed even though it wrote META-INF/services.
  options.compilerArgs.addAll(
    listOf("-Werror", "-Xlint:all", "-Xlint:-options", "-Xlint:-processing"),
  )
}

dependencies {
  implementation(platform(libs.opentelemetryInstrumentationBomAlpha))

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
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeader(
      """
      // SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
      // SPDX-License-Identifier: Apache-2.0
      """.trimIndent() + "\n\n",
      "(plugins|import|description|base)",
    )
  }
}
