# Configuration

## Dash0-specific environment variables

| Variable | Effect |
|---|---|
| `DASH0_OTEL_COLLECTOR_BASE_URL` | Sets the OTLP endpoint, unless `OTEL_EXPORTER_OTLP_ENDPOINT` is already set, which wins. |
| `DASH0_DISABLE` | When set to `true`, turns the SDK into a no-op so the process exports nothing. |

Both are read by `Dash0ConfigurationCustomizerProvider` in `custom/`, an
`AutoConfigurationCustomizerProvider`.

The two differ in what they beat, and the difference is deliberate.

`DASH0_DISABLE` overrides. Setting it to `true` wins even over
`OTEL_SDK_DISABLED=false`, because it is a kill switch and a kill switch that
another setting can quietly cancel is not one.

`DASH0_OTEL_COLLECTOR_BASE_URL` is a default. An `OTEL_EXPORTER_OTLP_ENDPOINT`
set on the workload wins over it. The Dash0 name is what a single
`/etc/opentelemetry/injector/default_env.conf` puts on a whole host, and a
host-wide default should not silently beat a setting someone made on one
workload.

This matches the Python and Ruby distributions in both halves. Python assigns
`OTEL_SDK_DISABLED` unconditionally but reaches for `environ.setdefault` for
the endpoint, and Ruby refuses to boot at all on `DASH0_DISABLE` while using
`Environment.set_default` for the endpoint. The Node.js distribution still
differs on the endpoint: it passes the base URL to the exporter constructor in
`src/2.x/init.ts`, which the SDK prefers over the environment.

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
operator meant the opposite. The Dash0 Python and Ruby distributions compare
the same way, with `value.strip().lower() == "true"` in `settings.py` and
`ENV.fetch(name, '').strip.casecmp('true').zero?` in `environment.rb`. Node.js
is the exception again: `src/index.ts` lowercases without trimming, so
`DASH0_DISABLE=" true "` disables Java, Python and Ruby and leaves Node.js
instrumenting.

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
Two standard defaults are changed:

| Variable | Here | Upstream |
|---|---|---|
| `OTEL_TRACES_SAMPLER` | `always_on` | `parentbased_always_on` |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `database,code,rpc,messaging` | unset |

Upstream's configuration reference:
https://opentelemetry.io/docs/zero-code/java/agent/configuration/

### `OTEL_TRACES_SAMPLER`

The distribution supplies `otel.traces.sampler=always_on`, so the SDK records
and exports every span the application starts. Setting `OTEL_TRACES_SAMPLER` or
`-Dotel.traces.sampler` on the workload replaces it: the value is registered
through `addPropertiesSupplier`, which both of those beat.

The specification's default is `parentbased_always_on`. `ParentBased` holds five
sub-samplers, and the `root` one covers only a span with no parent. When a
parent arrives in a `traceparent` header, `remote_parent_sampled` or
`remote_parent_not_sampled` makes the decision instead, and those two default to
`AlwaysOn` and `AlwaysOff`. A caller that sends a `traceparent` with the sampled
flag off therefore silences every span in the service handling that request, and
in every service it calls.

That caller is often outside your organization, which means your clients decide
whether you get to trace your own API. Resetting the trace context at your
network boundary fixes it. Getting that right at every entry point is harder
than it sounds, so the usual result is missing traces and no obvious cause.

`always_on` takes the incoming trace context out of the decision. Dash0 samples
traces after ingestion rather than inside your process, using Signal Control,
which evaluates a whole trace at once:
https://dash0.com/docs/dash0/telemetry/signal-control/sample-traces
A sampler in your process drops a span before it ever reaches that evaluation.

Set the sampler yourself to override the default:

```bash
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.1
```

Your application exports more spans than it would under
`parentbased_always_on`, which costs CPU in the process and bandwidth to your
collector. Cut the volume with Signal Control, or with a sampling processor in a
collector you run, rather than in the SDK.

Outgoing requests now carry a `traceparent` with the sampled flag set. A
downstream service still on `parentbased_always_on` will sample the traces you
start. That is `ParentBased` deferring to an incoming flag again, this time in
someone else's process rather than yours.

Declarative configuration does not apply this default either, for the reason the
next section gives.

### `OTEL_SEMCONV_STABILITY_OPT_IN`

The distribution sets `otel.semconv-stability.opt-in=database,code,rpc,messaging`,
so your spans carry the newer attribute names from the first day you run them
rather than the superseded ones. Setting `OTEL_SEMCONV_STABILITY_OPT_IN` or
`-Dotel.semconv-stability.opt-in` on the workload replaces the whole value; a
blank one is treated as unset.

The value is a comma-separated list of domains. Agent 2.33.1 accepts five, and
the two columns below differ in what the semantic conventions working group has
settled:

| Domain | Without the opt-in | With it |
|---|---|---|
| `database` | old | stable |
| `code` | old | stable |
| `rpc` | old | experimental |
| `messaging` | old | experimental |
| `service.peer` | old | experimental |

`http` is not in the list. HTTP semantic conventions are stable, the flag that
selected them is gone, and the agent emits the stable attribute names whatever
this variable says.

`service.peer` is not in the default either, because its experimental attributes
are expected to change again and adopting them now buys a second rename later.
`rpc` and `messaging` are in it even though they are experimental, because the
working group considers the current shape close to final.

The database attributes are the ones most people notice. Without the opt-in a
JDBC span carries `db.statement`, `db.system`, `db.name` and `db.operation`;
with it, `db.query.text`, `db.system.name`, `db.namespace` and
`db.operation.name`.

Attribute names are not the only thing that changes. Span names, span kinds,
metric names and one metric unit change with them, and a dashboard, query or
alert written against any of these stops matching with no error anywhere:

| What changes | Without the opt-in | With it |
|---|---|---|
| JDBC span name | `<operation> <database>.<table>` | a summary of the query |
| RPC span name, except gRPC | `<service>/<method>` | `<method>` |
| Messaging receive span kind | `CONSUMER` | `CLIENT` |
| Messaging send span kind, when the span context is not propagated | `PRODUCER` | `CLIENT` |
| RabbitMQ `basicGet` receive span | not recorded | recorded |
| Connection pool metrics | `db.client.connections.*` | `db.client.connection.*` |
| Connection pool metric attributes | `pool.name`, `state` | `db.client.connection.pool.name`, `db.client.connection.state` |
| `create_time`, `wait_time`, `use_time` | milliseconds | **seconds** |
| Database operation metrics | none | `db.client.operation.duration` |
| Messaging metrics | none | `messaging.client.operation.duration`, `messaging.client.sent.messages`, `messaging.process.duration`, `messaging.client.consumed.messages` |

The unit change is the one to check first. A threshold written against
`db.client.connections.wait_time` in milliseconds carried over to
`db.client.connection.wait_time` in seconds is a thousand times too high, so the
alert stops firing rather than failing.

Three instrumentations differ from the table:

- gRPC span names never change. gRPC does not use the shared RPC span name
  extractor, and its spans carry the full method name whatever this setting
  says.
- Pulsar loses metrics rather than gaining them. It is the one instrumentation
  in agent 2.33.1 still emitting the pre-1.43 `messaging.publish.duration`,
  `messaging.receive.duration` and `messaging.receive.messages`, and the opt-in
  stops all three. Every other messaging instrumentation records none of them
  either way, so the stable instruments are purely an addition there.
- Kafka receive spans are unaffected in a default agent. That instrumentation
  stays off until
  `otel.instrumentation.common.messaging.experimental.receive-telemetry.enabled`
  turns it on. RabbitMQ is the opposite case: its receive instrumenter is
  enabled by this opt-in on its own, so an application polling a queue with
  `basicGet` starts producing one span per call.

Append `/dup` to a domain to emit both sets at once, as in
`database/dup,code,rpc,messaging`. That doubles the attributes on every affected
span and is a migration aid rather than a resting state. It applies to
attributes only: the connection pool instruments are not dual-emitted, so
`database/dup` still renames them and still moves the three histograms to
seconds.

To keep the superseded names everywhere, set the variable to a value that names
no domain:

```bash
export OTEL_SEMCONV_STABILITY_OPT_IN=none
```

`none` is not a domain and selects nothing, which is what leaves every domain on
its upstream default. Two other ways of saying it do not work. Clearing the
variable does not, because a blank value is treated as unset and the default
applies again. Writing the setting in the properties file named by
`otel.javaagent.configuration-file` does not either: that file reaches
`ConfigProperties`, which is not where the agent reads this setting from, so the
line is ignored and the default applies over it. Use the environment variable or
`-Dotel.semconv-stability.opt-in`.

Unlike every other setting on this page, this one is applied by calling
`System.setProperty` during agent startup, not by returning it from an
autoconfiguration hook. The agent resolves it in `SemconvSelectionResolver`
through `SystemProperty.getString`, which reads `System.getProperty` and then
`System.getenv` and consults the SDK's resolved configuration for it in no other
way. A value returned from `addPropertiesSupplier` or `addPropertiesCustomizer`
reaches `ConfigProperties` and stops there, so the agent never sees it, the old
attribute names keep arriving, and nothing in the log says why. Upstream
documents the same restriction for the sibling setting
`otel.semconv-stability.preview`.

The guard reads both sources before setting anything: the system property is
left alone when it already has a value, and nothing is set at all when
`OTEL_SEMCONV_STABILITY_OPT_IN` is in the environment, since a system property
would shadow the variable the agent reads next.

Declarative configuration does not apply this default either, for the reason the
next section gives. A file expresses the same choice as `stability_opt_in_list`
under `general`, which `general.db.semconv.version` and its siblings then beat
per domain.

## Declarative configuration

`OTEL_CONFIG_FILE` points the agent at a YAML file that replaces environment
configuration entirely. This distribution does not ship one, and does not yet
work correctly when one is supplied. The variable is named `OTEL_CONFIG_FILE`;
`OTEL_EXPERIMENTAL_CONFIG_FILE` is read by nothing in agent 2.33.1.

Observed on agent 2.33.1 with a file declaring only `service.name` and a console
metric exporter, the exported resource is:

```
service.name="declarative-probe"
telemetry.distro.name="opentelemetry-javaagent"
telemetry.distro.version="dash0-0.1.0-SNAPSHOT-otel-2.33.1"
telemetry.sdk.language="java"
telemetry.sdk.name="opentelemetry"
telemetry.sdk.version="1.67.0"
```

The same application configured through the environment exports, among other
attributes:

```
service.name="env-probe"
telemetry.distro.name="dash0-java"
telemetry.distro.version="0.1.0-SNAPSHOT"
telemetry.sdk.language="java"
telemetry.sdk.name="opentelemetry"
telemetry.sdk.version="1.67.0"
host.name="demoman"
os.type="linux"
process.pid=343529
```

Three things differ. `DistroResourceProvider` does not run, so
`telemetry.distro.name` reverts to upstream's `opentelemetry-javaagent`; the
version string survives only because upstream's own
`io.opentelemetry.javaagent.tooling.resources.ResourceCustomizerProvider` reads
it from the agent jar manifest, which this build writes. No
`AutoConfigurationCustomizerProvider` runs at all, so `DASH0_DISABLE`,
`DASH0_OTEL_COLLECTOR_BASE_URL`, the `always_on` sampler default and the
semantic convention opt-in default are silently ignored; a file that omits
`sampler` gets the specification's `parentbased_always_on`. Every upstream host,
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
