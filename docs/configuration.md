# Configuration

## Dash0-specific environment variables

**None yet.**

The Dash0 Node.js and Python distributions expose a `DASH0_*` surface
(`DASH0_OTEL_COLLECTOR_BASE_URL`, `DASH0_DISABLE`,
`DASH0_AUTOMATIC_SERVICE_NAME`, `DASH0_BOOTSTRAP_SPAN`,
`DASH0_FLUSH_ON_SIGTERM_SIGINT`). Whether Java should match it is an open
decision; see the repository README.

Some of what those variables provide is already covered by the upstream agent,
which is part of why the decision is not obvious:

| Node.js / Python variable | Java equivalent today |
|---|---|
| `DASH0_DISABLE` | `OTEL_JAVAAGENT_ENABLED=false`, or `OTEL_SDK_DISABLED=true` |
| `DASH0_AUTOMATIC_SERVICE_NAME` | `JarServiceNameDetector` and the Spring Boot application-name detector, both on by default |
| `DASH0_FLUSH_ON_SIGTERM_SIGINT` | the JVM shutdown hook the agent already registers |
| `DASH0_OTEL_COLLECTOR_BASE_URL` | no equivalent; use `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `DASH0_BOOTSTRAP_SPAN` | no equivalent |

## Standard OpenTelemetry variables

All standard `OTEL_*` configuration works exactly as it does with the upstream
agent, because this distribution does not change how configuration is read.

The distribution currently sets **no defaults of its own**. Anything it sets in
future belongs in an `AutoConfigurationCustomizerProvider` in `custom/`, and must
be added to the table above at the same time.

Upstream's configuration reference:
https://opentelemetry.io/docs/zero-code/java/agent/configuration/
