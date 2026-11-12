// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  base
  id("com.diffplug.spotless")
}

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
}

extra["opentelemetryJavaagentVersion"] = libs.versions.opentelemetryJavaagent.get()

val LICENCE_HEADER =
  """
  // SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
  // SPDX-License-Identifier: Apache-2.0
  """.trimIndent() + "\n\n"

val KOTLIN_HEADER_ENDS_AT =
  "(plugins|import|pluginManagement|dependencyResolutionManagement|rootProject|@file)"
