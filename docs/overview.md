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
| all of the above under `OTEL_CONFIG_FILE` | `Dash0DeclarativeConfigurationCustomizerProvider` |
| the configuration file a Dash0 Linux package installs, when `OTEL_CONFIG_FILE` is unset | `Dash0DefaultConfigurationFileProvider` |

The first two replace the upstream agent's own values, which report
`opentelemetry-java-instrumentation`.

Two environment variables are read: `DASH0_OTEL_COLLECTOR_BASE_URL` sets the
OTLP endpoint and `DASH0_DISABLE=true` turns the SDK into a no-op. Both of them,
and both attributes above, apply under `OTEL_CONFIG_FILE` as well. With
`OTEL_CONFIG_FILE` unset, the SDK is configured from
`/etc/opentelemetry/java/otel-config.yaml` when that file exists, which is where
a Dash0 Linux package installs it. Three standard defaults are changed:
`OTEL_TRACES_SAMPLER` is `always_on`, `OTEL_PROPAGATORS` is
`tracecontext,baggage,xray`, which adds an `X-Amzn-Trace-Id` header to every
outgoing request, and `OTEL_SEMCONV_STABILITY_OPT_IN` is
`database,code,rpc,messaging`, which renames attributes on database, code, RPC
and messaging spans. See [configuration](configuration).

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

### Declarative configuration: decided

`OTEL_CONFIG_FILE` is supported, and so is the file a Dash0 Linux package
installs at `/etc/opentelemetry/java/otel-config.yaml`, which configures the SDK
when `OTEL_CONFIG_FILE` is unset. Declarative configuration consults a different
set of SPIs, so the distribution registers a
`DeclarativeConfigurationCustomizerProvider` that writes the same settings and
the same attributes into the parsed configuration model, and a
`DeclarativeConfigurationProvider` that supplies the default file. One rule is
worth knowing before writing a file: `DASH0_OTEL_COLLECTOR_BASE_URL` sets the
endpoint of an exporter the file declared and left either without one or at the
OTLP specification default, it never adds an exporter the file did not declare,
and an `OTEL_EXPORTER_OTLP_ENDPOINT` set on the workload keeps the endpoint the
file names. See
[configuration](configuration).
