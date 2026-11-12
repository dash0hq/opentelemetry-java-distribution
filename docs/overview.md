# Overview

The Dash0 distribution of OpenTelemetry for Java is a thin layer on top of the
upstream [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation).

Releases carry the repackaged agent jar and the extension jar as assets, and are
published at https://github.com/dash0hq/opentelemetry-java-distribution/releases.

## What it does today

| Attribute | Where it comes from |
|---|---|
| `telemetry.distro.name`, set to `dash0-java` | `DistroResourceProvider` |
| `telemetry.distro.version`, the version of this distribution | `DistroResourceProvider` |
| whatever a Linux package writes beside the agent jar, such as `dash0.package.name` | `Dash0PackageResourceProvider` |

The first two replace the upstream agent's own values, which report
`opentelemetry-java-instrumentation`.

Two environment variables are read: `DASH0_OTEL_COLLECTOR_BASE_URL` sets the
OTLP endpoint and `DASH0_DISABLE=true` turns the SDK into a no-op. See
[configuration](configuration).

Two standard OpenTelemetry defaults are changed. The trace sampler is
`always_on` rather than `parentbased_always_on`, so an incoming trace context
cannot decide whether this process is traced. And MDC entries are captured on
exported log records, except where the key looks like a credential, so the
identifiers an application puts in MDC become attributes you can filter and
group by. Both are set out in [configuration](configuration), including how to
turn either off and what capturing MDC exposes.

Everything else is upstream behaviour, unchanged.

## Architecture

```
custom/           every Dash0-authored SDK customisation, and nothing else
bootstrap/        classes needing the bootstrap class loader (empty for now)
agent/            delivery shape 1: the repackaged agent jar
agentextension/   delivery shape 2: the standalone extension jar
smoke-tests/      checks the assembled artifacts and a real instrumented JVM
buildSrc/         the packaging recipe, copied from upstream on purpose
```

## Open decisions

### Delivery shape: decided

The repackaged agent jar is the product. Both are still built, from the same
`custom/` module:

- `dash0-opentelemetry-javaagent.jar`, the upstream agent repackaged with the
  Dash0 code folded in, used as `-javaagent:<jar>`. **This is what Dash0 ships
  and supports.** It matches the Dash0 operator's integration point, a single
  file on a mounted volume referenced from `JAVA_TOOL_OPTIONS`.
- `dash0-opentelemetry-agentextension.jar`, the same code as an extension loaded
  by the unmodified upstream agent via `OTEL_JAVAAGENT_EXTENSIONS`. Kept as a
  supported secondary artifact, the way Elastic ships both.

### Rationale: still open

The Dash0 Python distribution exists largely to remove native `google.protobuf`
and `grpcio` dependencies so it is safe to inject into an arbitrary process.
That reasoning does not carry over: the Java agent is already a single shaded
jar in its own class loader, and a class's identity in Java is its name together
with the loader that loaded it, so the agent's libraries and the application's
cannot collide.

The remaining candidates are Dash0 configuration parity with the Node.js and
Python distributions, branding, and an independent support and upgrade policy.
That is a product decision, tracked in OSS-41
(https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro).

### Declarative configuration: deferred

`OTEL_CONFIG_FILE` disables every customisation listed above, because
declarative configuration uses a different pair of SPIs. The measurement and
the design that would fix it are in [configuration](configuration). It is
planned as a separate release after v0.1.0.
