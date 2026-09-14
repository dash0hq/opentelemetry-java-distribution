// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.agent-packaging-conventions")
}

base.archivesName.set("dash0-opentelemetry-javaagent")

description = "Dash0 distribution of the OpenTelemetry Java agent, as a single repackaged jar"

// Delivery shape one: the upstream agent repackaged with :custom folded in, used
// as -javaagent:dash0-opentelemetry-javaagent.jar. See :agentextension for the
// other shape. Both are built from the same :custom module.
dependencies {
  add("bootstrapLibs", project(":bootstrap"))
  add("javaagentLibs", project(":custom"))
  add("upstreamAgent", libs.opentelemetryJavaagent)
}
