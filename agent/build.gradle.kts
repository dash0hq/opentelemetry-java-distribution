// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.agent-packaging-conventions")
}

base.archivesName.set("dash0-opentelemetry-javaagent")

description = "Dash0 distribution of the OpenTelemetry Java agent, as a single repackaged jar"

dependencies {
  add("bootstrapLibs", project(":bootstrap"))
  add("javaagentLibs", project(":custom"))
  add("upstreamAgent", libs.opentelemetryJavaagent)
}
