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

/** Contributes {@link DistroResource} to the SDK's resource. */
@AutoService(ResourceProvider.class)
public final class DistroResourceProvider implements ResourceProvider, Ordered {

  /**
   * Applied after every provider that uses the default order of {@code 0}, including upstream's.
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
