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
 * Contributes {@link Dash0PackageResource} to the SDK's resource.
 *
 * <p>The file it reads is absent in every way of running the agent other than from a Dash0 Linux
 * package, and its absence is not an error. Anything else setting the same key wins.
 *
 * <p>See {@code docs/resource-detection.md}.
 */
@AutoService(ResourceProvider.class)
public final class Dash0PackageResourceProvider implements ResourceProvider, Ordered {

  /** Applied before every other provider, so every one of them can overwrite these attributes. */
  @Override
  public int order() {
    return Integer.MIN_VALUE;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Never throws. Every failure degrades to an empty resource.
   */
  @Override
  public Resource createResource(ConfigProperties config) {
    return Dash0PackageResource.get();
  }
}
