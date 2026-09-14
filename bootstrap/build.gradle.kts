// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

plugins {
  id("dash0.java-conventions")
}

// Classes that must live in the bootstrap class loader, visible both to the
// instrumented application and to the agent.
//
// Empty today. It exists because adding the module later means re-deriving the
// bucket split in dash0.agent-packaging-conventions, and because putting a class
// in the wrong class loader is one of the failure modes that produces no error
// message at all.
