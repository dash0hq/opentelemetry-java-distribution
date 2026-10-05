# Overview

The Dash0 distribution of OpenTelemetry for Java is a thin layer on top of the
upstream [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation).

> **This is scaffolding.** The repository builds and is tested, but it has no
> feature set yet. The shape is decided; what the distribution is *for* is not.
> See [Open decisions](#open-decisions).

## What it does today

| Attribute | Value |
|---|---|
| `telemetry.distro.name` | `dash0-java` |
| `telemetry.distro.version` | the version of this distribution |

Both replace the upstream agent's own values, which report
`opentelemetry-java-instrumentation`. Everything else is upstream behaviour,
unchanged.

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
That is a product decision, tracked against OSS-35.
