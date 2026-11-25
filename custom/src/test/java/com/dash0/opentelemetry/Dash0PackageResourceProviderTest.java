/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class Dash0PackageResourceProviderTest {

  @Test
  void isRegisteredAsAResourceProviderService() {
    assertThat(ServiceLoader.load(ResourceProvider.class))
        .anyMatch(Dash0PackageResourceProvider.class::isInstance);
  }

  @Test
  void appliesBeforeEveryOtherProvider() {
    assertThat(new Dash0PackageResourceProvider().order()).isEqualTo(Integer.MIN_VALUE);
  }
}
