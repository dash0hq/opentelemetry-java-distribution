# Configuration

## Dash0-specific environment variables

| Variable | Effect |
|---|---|
| `DASH0_OTEL_COLLECTOR_BASE_URL` | Sets the OTLP endpoint. Overrides `OTEL_EXPORTER_OTLP_ENDPOINT` when both are set. |
| `DASH0_DISABLE` | When set to `true`, turns the SDK into a no-op so the process exports nothing. |

Both are read by `Dash0ConfigurationCustomizerProvider` in `custom/`, an
`AutoConfigurationCustomizerProvider` registered through
`addPropertiesCustomizer`. A customizer added that way runs after the
environment has been read and its return value replaces what was read, which is
why `DASH0_OTEL_COLLECTOR_BASE_URL` wins over `OTEL_EXPORTER_OTLP_ENDPOINT`.
`addPropertiesSupplier`, the other half of the same interface, supplies defaults
that an environment variable overrides, and is the wrong one for this.

### `DASH0_OTEL_COLLECTOR_BASE_URL`

Translated to `otel.exporter.otlp.endpoint`. A trailing slash is stripped,
because the OTLP exporter appends `/v1/traces`, `/v1/metrics` and `/v1/logs`
itself and would otherwise produce a doubled separator.

The variable names a base URL rather than a signal-specific one, so it has no
per-signal counterpart. Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` and its
siblings directly when signals need to go to different places; those are read
after the endpoint this variable sets, so they still take effect.

### `DASH0_DISABLE`

Compared against the exact string `true`, case-insensitively and after trimming
whitespace. Every other value, including `1`, `yes` and `on`, leaves the
distribution enabled. Guessing that `1` means true would silence a host whose
operator meant the opposite, and the Dash0 Node.js and Ruby distributions
compare the same way.

Setting it translates to `otel.sdk.disabled=true`. The agent still attaches and
still instruments the application; the SDK it hands those instrumentations is a
no-op, so nothing is recorded and nothing is exported. `OTEL_JAVAAGENT_ENABLED=false`
is the heavier alternative, which stops the agent from attaching at all.

### Variables this distribution does not implement

`DASH0_AUTOMATIC_SERVICE_NAME`, `DASH0_BOOTSTRAP_SPAN` and
`DASH0_FLUSH_ON_SIGTERM_SIGINT` exist in the Node.js and Python distributions
and are absent here, because the upstream Java agent already covers two of the
three and the third has no Java counterpart:

| Variable | Why it is absent |
|---|---|
| `DASH0_AUTOMATIC_SERVICE_NAME` | `JarServiceNameDetector` and the Spring Boot application-name detector both run by default. |
| `DASH0_FLUSH_ON_SIGTERM_SIGINT` | The agent registers a JVM shutdown hook that flushes. |
| `DASH0_BOOTSTRAP_SPAN` | No Java equivalent, and no demand recorded for one. |

## Standard OpenTelemetry variables

All standard `OTEL_*` configuration works as it does with the upstream agent.
The two variables above are the only defaults this distribution changes.

Upstream's configuration reference:
https://opentelemetry.io/docs/zero-code/java/agent/configuration/

## Declarative configuration

`OTEL_CONFIG_FILE` points the agent at a YAML file that replaces environment
configuration entirely. This distribution does not ship one, and does not yet
work correctly when one is supplied. The variable is named `OTEL_CONFIG_FILE`;
`OTEL_EXPERIMENTAL_CONFIG_FILE` is read by nothing in agent 2.32.0.

Measured on agent 2.32.0 with a file declaring only `service.name` and a console
metric exporter, the exported resource is:

```
service.name="declarative-probe"
telemetry.distro.name="opentelemetry-javaagent"
telemetry.distro.version="dash0-0.1.0-SNAPSHOT-otel-2.32.0"
telemetry.sdk.language="java"
telemetry.sdk.name="opentelemetry"
telemetry.sdk.version="1.66.0"
```

The same application configured through the environment exports:

```
service.name="env-probe"
telemetry.distro.name="dash0-java"
telemetry.distro.version="0.1.0-SNAPSHOT"
telemetry.sdk.language="java"
telemetry.sdk.name="opentelemetry"
telemetry.sdk.version="1.66.0"
host.name="demoman"
os.type="linux"
process.pid=583734
```

Three things differ. `DistroResourceProvider` does not run, so
`telemetry.distro.name` reverts to upstream's `opentelemetry-javaagent`; the
version string survives only because upstream's own
`io.opentelemetry.javaagent.tooling.resources.ResourceCustomizerProvider` reads
it from the agent jar manifest, which this build writes. No
`AutoConfigurationCustomizerProvider` runs at all, so `DASH0_DISABLE` and
`DASH0_OTEL_COLLECTOR_BASE_URL` are silently ignored. Every upstream host,
process and OS detector disappears as well.

The cause is that declarative configuration uses two different SPIs.
`AutoConfigurationCustomizerProvider` and `ResourceProvider` are not consulted;
`DeclarativeConfigurationCustomizerProvider` and
`io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider` are. Upstream
keeps its own distro attributes across the switch by registering
`ResourceCustomizerProvider` under the first of those. Supporting declarative
configuration here means implementing the same pair, and that is planned as a
separate release after v0.1.0 rather than part of it.

The agent also logs a warning for the file above:

```
Configuration file_format '1.0' does not exactly match expected version '1.1'.
```

Declaring `1.1` is not a fix, because the vendored `otel-config.yaml` upstream
ships for Java declares `1.0` as well.
