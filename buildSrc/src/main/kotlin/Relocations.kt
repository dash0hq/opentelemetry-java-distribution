// SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
// SPDX-License-Identifier: Apache-2.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/**
 * Package relocations applied to every jar that ends up inside the agent.
 *
 * These rules are copied from upstream's canonical example at
 * examples/distro/buildSrc/src/main/kotlin/Relocations.kt and MUST be kept in
 * sync with it. They are not a style choice.
 *
 * The upstream agent relocates its own dependencies into
 * io.opentelemetry.javaagent.shaded.*, so code compiled against the ordinary
 * io.opentelemetry.api names does not resolve at runtime unless it is rewritten
 * the same way.
 *
 * Note the two exclusions below: resource providers must NOT be relocated,
 * because they live in the agent class loader rather than the bootstrap one.
 * Getting this wrong produces a resource provider that is silently never
 * invoked, with no error anywhere.
 */
fun relocatePackages(shadowJar: ShadowJar) {
  // Rewrite dependencies calling Logger.getLogger.
  shadowJar.relocate("java.util.logging.Logger", "io.opentelemetry.javaagent.bootstrap.PatchLogger")

  // Prevents conflict with library instrumentation, since these classes live in
  // the bootstrap class loader.
  shadowJar.relocate("io.opentelemetry.instrumentation", "io.opentelemetry.javaagent.shaded.instrumentation") {
    exclude("io.opentelemetry.instrumentation.resources.*")
    exclude("io.opentelemetry.instrumentation.spring.resources.*")
  }

  // The OpenTelemetry API lives in the bootstrap class loader.
  shadowJar.relocate("io.opentelemetry.api", "io.opentelemetry.javaagent.shaded.io.opentelemetry.api")
  shadowJar.relocate("io.opentelemetry.semconv", "io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv")
  shadowJar.relocate("io.opentelemetry.context", "io.opentelemetry.javaagent.shaded.io.opentelemetry.context")
  shadowJar.relocate("io.opentelemetry.common", "io.opentelemetry.javaagent.shaded.io.opentelemetry.common")

  // Extensions used by instrumentation modules. These live in the agent class
  // loader and are injected into the application's class loader by the
  // instrumentation modules that use them.
  shadowJar.relocate("io.opentelemetry.contrib.awsxray", "io.opentelemetry.javaagent.shaded.io.opentelemetry.contrib.awsxray")
  shadowJar.relocate("io.opentelemetry.extension.kotlin", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.kotlin")
}
