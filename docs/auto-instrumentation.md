# Auto-instrumentation

This distribution adds **no instrumentation of its own**. The full instrumentation
set comes from the upstream OpenTelemetry Java agent, unmodified.

The version that determines that set is pinned in one place,
`gradle/libs.versions.toml`:

```toml
opentelemetryJavaagent = "2.32.0"
```

The library list for that version is upstream's:
https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md

This is a real difference from the Dash0 Python distribution, which has to pin
every `opentelemetry-instrumentation-*` package explicitly because upstream
Python ships them separately. The Java agent bundles them all, so there is no
list to maintain here.

## Enabling and disabling

Upstream's mechanisms work unchanged:

| Goal | Setting |
|---|---|
| Disable one instrumentation | `OTEL_INSTRUMENTATION_<NAME>_ENABLED=false` |
| Disable all by default | `OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false` |
| Disable the agent entirely | `OTEL_JAVAAGENT_ENABLED=false` |

## Adding Dash0-specific instrumentation

Not done today, and it is a larger step than adding an SPI implementation. An
`InstrumentationModule` additionally requires upstream's muzzle Gradle plugins
(`io.opentelemetry.instrumentation.muzzle-generation` and `muzzle-check`), four
extra Gradle configurations, and a declaration of the exact library version
ranges supported. Muzzle is what stops instrumentation from being applied to a
library version it does not understand, so it is not optional.
