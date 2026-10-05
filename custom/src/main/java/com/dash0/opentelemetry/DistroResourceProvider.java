/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.Ordered;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Contributes {@link DistroResource} to the SDK's resource.
 *
 * <p>{@code @AutoService} generates the {@code META-INF/services} entry that makes this
 * discoverable at runtime. This works identically whether the class is packaged into the repackaged
 * agent jar or loaded from the standalone extension jar, which is why the choice of delivery shape
 * does not affect this code.
 *
 * <p>Note that resource providers are deliberately excluded from package relocation; see {@code
 * buildSrc/src/main/kotlin/Relocations.kt}.
 */
@AutoService(ResourceProvider.class)
public final class DistroResourceProvider implements ResourceProvider, Ordered {

  /**
   * Applied after every provider that uses the default order of {@code 0}.
   *
   * <p>This is load-bearing, not a preference. The agent ships its own {@code
   * io.opentelemetry.javaagent.tooling.resources.DistroResourceProvider}, which sets {@code
   * telemetry.distro.name} to {@code opentelemetry-java-instrumentation}. Resource providers are
   * applied in ascending order and later ones win, so without a higher order than upstream's the
   * Dash0 value is silently overwritten and all telemetry is attributed to the upstream agent.
   *
   * <p>There is no error when this is wrong. The only thing that catches it is asserting on the
   * attributes a real instrumented JVM exports, which is what {@code DistroResourceAttributesTest}
   * in {@code :smoke-tests} does.
   */
  @Override
  public int order() {
    return 100;
  }

  @Override
  public Resource createResource(ConfigProperties config) {
    return DistroResource.get();
  }
}
