// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  base
  id("com.diffplug.spotless")
}

// The five subprojects apply dash0.java-conventions, which formats their own
// sources. Nothing covered this file, settings.gradle.kts or anything under
// buildSrc/, which is where the packaging recipe lives and is the hardest code
// in the repository to review by eye.
spotless {
  kotlinGradle {
    target(
      "*.gradle.kts",
      "buildSrc/*.gradle.kts",
      "buildSrc/src/main/kotlin/*.gradle.kts",
    )
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeader(LICENCE_HEADER, KOTLIN_HEADER_ENDS_AT)
  }
  // buildSrc/src/main/kotlin/Relocations.kt is deliberately absent. It is a
  // verbatim copy of upstream's file, and scripts/check-relocations.sh
  // compares it against upstream line by line, so a formatter that wrapped
  // one shadowJar.relocate(...) call across two lines would stop that
  // comparison from extracting the rule at all.
}

// Exposed so the agent manifest can record which upstream agent this
// distribution was built on, the way every vendor distribution does.
extra["opentelemetryJavaagentVersion"] = libs.versions.opentelemetryJavaagent.get()

val LICENCE_HEADER =
  """
  // SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
  // SPDX-License-Identifier: Apache-2.0
  """.trimIndent() + "\n\n"

// Where the header stops and the file begins. Every Kotlin build file here
// opens with one of these, and .kt files open with an import.
val KOTLIN_HEADER_ENDS_AT =
  "(plugins|import|pluginManagement|dependencyResolutionManagement|rootProject|@file)"
