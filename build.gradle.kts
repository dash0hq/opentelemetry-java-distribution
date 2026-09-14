// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  base
}

// Exposed so the agent manifest can record which upstream agent this
// distribution was built on, the way every vendor distribution does.
extra["opentelemetryJavaagentVersion"] = libs.versions.opentelemetryJavaagent.get()
