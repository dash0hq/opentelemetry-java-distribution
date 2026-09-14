/*
 * SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class DistroResourceProviderTest {

  @Test
  void isRegisteredAsAResourceProviderService() {
    // This is the test that matters. If the @AutoService annotation processor
    // stops running, the class still compiles and every other test still
    // passes, but the agent never discovers the provider and the distro
    // attributes silently disappear from all telemetry.
    assertThat(ServiceLoader.load(ResourceProvider.class))
        .anyMatch(DistroResourceProvider.class::isInstance);
  }

  @Test
  void providesTheDistroResource() {
    assertThat(new DistroResourceProvider().createResource(null)).isEqualTo(DistroResource.get());
  }
}
