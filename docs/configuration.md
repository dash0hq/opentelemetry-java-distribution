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
| `DASH0_AUTOMATIC_SERVICE_NAME` | `JarServiceNameDetector` and the Spring Boot application-name detector both run by default; the `service.name` section of [resource detection](resource-detection) gives the value a JVM reports for each launch shape. |
| `DASH0_FLUSH_ON_SIGTERM_SIGINT` | The agent registers a JVM shutdown hook that flushes. |
| `DASH0_BOOTSTRAP_SPAN` | No Java equivalent, and no demand recorded for one. |

## Standard OpenTelemetry variables

Under environment configuration, all standard `OTEL_*` configuration works as it
does with the upstream agent. Three standard defaults are changed:

| Variable | This distribution | Upstream |
|---|---|---|
| `OTEL_TRACES_SAMPLER` | `always_on` | `parentbased_always_on` |
| `OTEL_PROPAGATORS` | `tracecontext,baggage,xray` | `tracecontext,baggage` |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `database,code,rpc,messaging` | unset |

Under a configuration file the SDK reads almost none of them, and that mode is now
reached without setting `OTEL_CONFIG_FILE` at all. [Declarative
configuration](#declarative-configuration) below gives the order the file is picked
in and what a file leaves unread.

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

Declarative configuration applies this default too, through a different
mechanism that the section on it below describes.

### Resource-provider toggles

The upstream agent ships eleven resource providers switched off, grouped into
five groups that `ResourceProviderPropertiesCustomizer` gates on one property
each:

| Property | Environment variable | Providers it covers | Default |
|---|---|---|---|
| `otel.resource.providers.aws.enabled` | `OTEL_RESOURCE_PROVIDERS_AWS_ENABLED` | `BeanstalkResourceProvider`, `Ec2ResourceProvider`, `EcsResourceProvider`, `EksResourceProvider`, `LambdaResourceProvider` | `false` |
| `otel.resource.providers.gcp.enabled` | `OTEL_RESOURCE_PROVIDERS_GCP_ENABLED` | `GCPResourceProvider` | `false` |
| `otel.resource.providers.azure.enabled` | `OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED` | `AzureAppServiceResourceProvider`, `AzureFunctionsResourceProvider`, `AzureVmResourceProvider` | `false` |
| `otel.resource.providers.cloudfoundry.enabled` | `OTEL_RESOURCE_PROVIDERS_CLOUDFOUNDRY_ENABLED` | `CloudFoundryResourceProvider` | `false` |
| `otel.resource.providers.host-id.enabled` | `OTEL_RESOURCE_PROVIDERS_HOST_ID_ENABLED` | `HostIdResourceProvider` | `false` |

Those five are the only correct toggles, and this distribution changes none of
the defaults.

`otel.java.enabled.resource.providers` is an allow-list rather than a second way
to spell these, and setting it switches off every provider it does not name.
[Resource detection](resource-detection) gives what a JVM exported when it was
set.

The hyphen in `otel.resource.providers.host-id.enabled` is not a typo, and the
environment spelling is still all underscores. Two different methods normalise a
name before the lookup. `ConfigUtil.normalizePropertyKey` lowercases it and
turns each hyphen into a dot, and is applied to a system property and to the key
a provider asks for. `ConfigUtil.normalizeEnvironmentVariableKey` lowercases it
and turns each underscore into a dot, and is applied to an environment variable.
Both `OTEL_RESOURCE_PROVIDERS_HOST_ID_ENABLED` and
`-Dotel.resource.providers.host-id.enabled` therefore arrive as
`otel.resource.providers.host.id.enabled`, and both were observed switching
`HostIdResourceProvider` on at agent 2.33.1. An environment variable whose name
carries a literal hyphen was observed doing nothing.

Ten of those eleven are cloud detectors, and three of the ten open a socket to a
metadata endpoint, so the `aws`, `azure` and `gcp` groups are not free to enable
on a host that is somewhere else. On a Linux host where traffic to
`169.254.169.254` is dropped rather than refused, `aws` adds 4.35 s and `azure`
adds 5.18 s to JVM startup. The `cloudfoundry` group reads `VCAP_APPLICATION`
and opens nothing. [Resource detection](resource-detection) has the per-detector
cost table, the experiment behind those two figures, the attributes each
detector contributes, and the reason this distribution leaves the cloud groups
off. The eleventh is `HostIdResourceProvider`, which the next section covers.

### `host.id`

`HostIdResourceProvider` is the one non-cloud provider the agent ships disabled,
and this distribution leaves it disabled. Switch it on with
`OTEL_RESOURCE_PROVIDERS_HOST_ID_ENABLED=true` only after reading what it does
on your platform.

On Linux it reads `/etc/machine-id`, falling back to
`/var/lib/dbus/machine-id`. Either file is baked into a container image at build
time, so every container started from one image reports the same `host.id`, and
the attribute groups unrelated workloads together instead of separating them.
The failure is confident rather than noisy: a wrong identifier arrives looking
exactly like a right one. Several Debian-derived JDK images go the other way and
ship an empty `/etc/machine-id` with no `/var/lib/dbus/machine-id` beside it,
`eclipse-temurin:21` among them. A container built on one of those exports no
`host.id` at all with the group switched on, and logs the reason at `FINE` only.

On macOS and Windows it forks a subprocess at startup, `/usr/sbin/ioreg` and
`reg.exe query HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography /v MachineGuid`
respectively, which costs startup time in every process the agent attaches to.
On BSD it reads `/etc/hostid` or runs `/bin/kenv`.

On a Linux host running the Dash0 Linux package rather than a container,
`/etc/machine-id` is a stable and correct identifier for that host, and
switching the group on is reasonable.

`OTEL_CONFIG_FILE` switches `host.id` on whichever way this group is set. The
customizer names the `host` detector in `resource.detection/development`, and
that detector supplies `host.id` beside `host.name` with no field to ask for one
without the other. So the same JVM reports `host.id` with a configuration file
and no `host.id` without one. See [Resource detection](resource-detection).

### `OTEL_PROPAGATORS`

The distribution supplies `otel.propagators=tracecontext,baggage,xray`, so
outgoing requests carry both a W3C `traceparent` and an AWS `X-Amzn-Trace-Id`
header, plus `baggage` when there is any. The X-Ray propagator is the
OpenTelemetry contrib one that the upstream agent already bundles; nothing is
added to the jar. Setting `OTEL_PROPAGATORS` or `-Dotel.propagators` replaces
the default exactly, through the same `addPropertiesSupplier` precedence as the
sampler.

The order matters on extraction. Each propagator runs in turn and a later one
that finds a valid context replaces an earlier one, so when a request carries
both headers the X-Ray context wins.

This keeps one trace across an AWS Application Load Balancer:

- The ALB keeps `Root` and `Parent` from an incoming `X-Amzn-Trace-Id` and may
  add fields such as `Self=`. Unknown fields are ignored on extraction.
- When a request arrives without `X-Amzn-Trace-Id`, the ALB adds one with a
  `Root` and no `Parent`. The X-Ray propagator treats a header without a
  `Parent` as no context at all, so the `traceparent` context extracted before
  it is kept.
- Both headers are written on injection, so a load balancer further downstream
  sees a `Parent`.

The flip side is that any valid `X-Amzn-Trace-Id` beats `traceparent`, even one
this service's direct caller didn't write. A hop that forwards incoming headers
unchanged but writes a fresh `traceparent`, e.g. a reverse proxy instrumented
with only `tracecontext`, passes along the `X-Amzn-Trace-Id` from the hop before
it. This service then parents its span to that earlier hop, and the proxy's span
ends up with no children. AWS services with X-Ray active tracing, such as API
Gateway, write a `Parent` that exists only in X-Ray, which has a similar effect.
Setting `OTEL_PROPAGATORS=tracecontext,baggage` restores `traceparent`-only
extraction.

`baggage` comes before `xray` because the two treat existing baggage
differently. The X-Ray propagator adds its `Lineage` field to the baggage
already extracted, while the baggage propagator replaces it, so the reverse
order would drop `Lineage` whenever both headers arrive. When an incoming
`X-Amzn-Trace-Id` carries `Lineage`, the entry stays in baggage for the rest of
the request. That means it is written to the outgoing W3C `baggage` header as
well as `X-Amzn-Trace-Id`, and it appears wherever baggage is copied onto spans
or logs.

Declarative configuration applies this default too, as
`propagator.composite_list`. The next section describes the mechanism.

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
JDBC span carries `db.statement`, `db.system`, `db.name`, `db.operation`,
`db.sql.table`, `db.user` and `db.connection_string`. With it, it carries
`db.query.text`, `db.system.name`, `db.namespace` and `db.query.summary`.

Three of those seven have no replacement, so no rewritten query recovers them:
`db.user` and `db.connection_string` are gone from the stable conventions, and
`db.sql.table` is dropped without `db.collection.name` taking its place, since
Cassandra is the only SQL client whose instrumentation asks for the operation
and collection pair. On a JDBC span the operation survives inside
`db.query.summary`, which holds `SELECT customers` for a query against one
table, and not as `db.operation.name`.

A non-SQL database span is the other way round. MongoDB, Redis, Elasticsearch
and the rest carry `db.operation.name` and `db.collection.name` with the opt-in,
so a filter on `db.operation` becomes a filter on `db.operation.name` there and
a filter on `db.query.summary` matches nothing.

Attribute names are not the only thing that changes. Span names, span kinds,
metric names and one metric unit change with them, and a dashboard, query or
alert written against any of these stops matching with no error anywhere:

| What changes | Without the opt-in | With it |
|---|---|---|
| Database span name, SQL clients | `<operation> <database>.<table>` | a summary of the query |
| Database span name, everything else | `<operation> <database>` | `<operation> <collection>` |
| RPC span name, except gRPC | `<service>/<method>` | `<method>` |
| Messaging span name | `<destination> <operation>`, as in `orders publish` | `<operation> <destination>`, as in `send orders` |
| Messaging receive span kind | `CONSUMER` | `CLIENT` |
| Messaging send span kind, when the span context is not propagated | `PRODUCER` | `CLIENT` |
| RabbitMQ `basicGet` receive span | not recorded | recorded |
| Connection pool metrics | `db.client.connections.usage`, `db.client.connections.max` | `db.client.connection.count`, `db.client.connection.limit` |
| The other connection pool metrics | `db.client.connections.*` | `db.client.connection.*`, same suffix |
| Connection pool metric attributes | `pool.name`, `state` | `db.client.connection.pool.name`, `db.client.connection.state` |
| `create_time`, `wait_time`, `use_time` | milliseconds | **seconds** |
| Database operation metrics | none | `db.client.operation.duration` |
| Messaging metrics | none | `messaging.client.operation.duration`, `messaging.client.sent.messages`, `messaging.process.duration`, `messaging.client.consumed.messages` |

The unit change is the one to check first. A threshold written against
`db.client.connections.wait_time` in milliseconds carried over to
`db.client.connection.wait_time` in seconds is a thousand times too high, so the
alert stops firing rather than failing.

The first database row covers every SQL client the agent instruments: JDBC,
Cassandra, R2DBC, the Vert.x SQL client and ClickHouse among them. The second
covers the rest, MongoDB and Redis and Elasticsearch among them, and its
right-hand cell has two fallbacks. An instrumentation reporting no collection is
named after its namespace, and one reporting neither after `<host>:<port>`,
which is where Redis and Elasticsearch land: a Redis span named `GET` today is
named `GET redis.example:6379` after the upgrade, one name per node rather than
one for the cluster. MongoDB is the other way round. Its name already carries
the collection, as `insert mydb.mycollection`, so the rename drops the database
and leaves `insert mycollection`. The messaging row covers every messaging
instrumentation, Kafka, JMS, RabbitMQ, SQS, Pulsar, RocketMQ and NATS among
them.

On a messaging span the operation word changes as well as its position: a Kafka
producer span named `orders publish` becomes `send orders`, and a consumer span
named `orders process` becomes `process orders`. Kafka's `poll` span is the one
to leave out while sizing the work: a default agent never records it, as the
Kafka bullet below explains.

Log records change too, in one case. The Logback and Log4j appenders write
`code` attributes on a log record only when the appender's experimental
code-attribute capture is turned on, which is off by default. Where it is on,
those records carry `code.file.path`, `code.function.name` and
`code.line.number` in place of `code.filepath`, `code.namespace`,
`code.function` and `code.lineno`.

Four instrumentations differ from the table:

- The AWS SDK names most of its client spans itself, as
  `<service>.<operation>`, and reads none of this setting to do it, so a
  DynamoDB or an S3 span keeps the name it has. Two parts of it do move: its SQS
  messaging spans are renamed like every other messaging span, and its RDS Data
  API spans take the query summary of the first database row.
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
span and is a migration aid rather than a resting state. `messaging/dup` covers
metrics too: it keeps Pulsar's three pre-1.43 instruments alongside the new
ones. The connection pool instruments are the exception, because the agent picks
one name for each of them rather than recording both, so `database/dup` still
renames them and still moves the three histograms to seconds.

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
Declarative configuration section below gives. A file expresses the same choice
as `stability_opt_in_list` under `general`, which `general.db.semconv.version`
and its siblings then beat per domain.

## Declarative configuration

A declarative configuration file is a YAML file that replaces environment
configuration entirely. The variable that names one is `OTEL_CONFIG_FILE`;
`OTEL_EXPERIMENTAL_CONFIG_FILE` is read by nothing in agent 2.33.1.

The distribution picks the file in this order:

1. `OTEL_CONFIG_FILE`, when it is set to anything that is not blank.
2. `/etc/opentelemetry/java/otel-config.yaml`, when that file exists.
3. Neither, in which case the SDK configures itself from the environment, as
   described in every section above.

A Dash0 Linux package installs the file at the path in step 2, so a host with
that package and no `OTEL_CONFIG_FILE` runs in declarative-configuration mode
from the file the package ships. A host or container without that file is
unaffected.

A host leaves step 2 by changing the file, not by setting a variable. Removing
or renaming `/etc/opentelemetry/java/otel-config.yaml` puts the next JVM back on
step 3, and a package upgrade that reinstalls the file puts it back on step 2.
Setting `OTEL_CONFIG_FILE` moves a single workload to step 1 without touching
the host. No variable selects step 3 while the file is there.

An `OTEL_CONFIG_FILE` naming a file that does not exist still wins. The SDK
reports the error and starts no telemetry, which is what makes a typo in the
path visible rather than silently replaced by step 2. The step is skipped for a
value that is empty or only whitespace.

`OTEL_SDK_DISABLED=true` also skips step 2, and it is the only variable that
keeps the default file from loading at all. The provider reads it from the
system property `otel.sdk.disabled`, then from the environment variable, and
then from the properties file `otel.javaagent.configuration-file` names, which
is the same set of three the SDK resolves it from under environment
configuration. The properties file reaches a declaratively configured SDK
through nothing of its own: `ConfigurationPropertiesSupplier` is an
`AutoConfigurationCustomizerProvider`, and the early return in
`maybeConfigureFromFile` happens first, so the provider reads that file itself.
The section on the customizer below lists every setting read that way. A declaratively configured SDK reads
no `OTEL_SDK_DISABLED`, so a host-wide default file that loaded anyway would
restart a workload whose operator had silenced it. The value is compared with
`Boolean.parseBoolean` on the raw string, which is what
`DefaultConfigProperties.getBoolean` does, so the file loads for exactly the
values that leave the SDK enabled under environment configuration. An explicit
`OTEL_CONFIG_FILE` is unaffected: step 1 is an opt-in, and the SDK has ignored
`OTEL_SDK_DISABLED` under one since declarative configuration existed.

The SDK consults a different set of SPIs in this mode.
`AutoConfigurationCustomizerProvider` and `ResourceProvider`, which carry
everything this distribution contributes under environment configuration, are
never loaded: `AutoConfiguredOpenTelemetrySdkBuilder.buildImpl` returns from
`maybeConfigureFromFile` before it reaches either. The three that are loaded are
`DeclarativeConfigurationProvider`, `DeclarativeConfigurationCustomizerProvider`
and `io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider`.

`Dash0DeclarativeConfigurationCustomizerProvider` in `custom/` registers under
the second of those. Its `addModelCustomizer` function receives the parsed
`OpenTelemetryConfigurationModel`, a mutable bean tree, and the SDK is built
from whatever it returns.

| Setting | What the customizer writes into the model |
|---|---|
| `DASH0_DISABLE=true` | `disabled: true`, which builds an SDK with no providers at all |
| `DASH0_OTEL_COLLECTOR_BASE_URL` | the `endpoint` of every OTLP exporter the file declared without one |
| the `always_on` sampler default | `tracer_provider.sampler`, unless the file declares a sampler |
| the `tracecontext,baggage,xray` propagators default | `propagator.composite_list`, unless the file declares a `composite` or a non-blank `composite_list` |
| `telemetry.distro.name` and `telemetry.distro.version` | `resource.attributes`, unless the file declares that name |
| `dash0.package.*`, read from beside the agent jar | `resource.attributes`, unless the file declares that name |
| the upstream `host`, `process` and `service` detectors | `resource.detection/development`, unless the file names them |

Every setting the customizer reads comes from the system property first, the
environment variable second, and the properties file
`otel.javaagent.configuration-file` names third, which is the order
`DefaultConfigProperties` resolves the same names in under environment
configuration. There is no `ConfigProperties` instance in this mode, so each
provider reads all three itself. `DASH0_DISABLE`,
`DASH0_OTEL_COLLECTOR_BASE_URL` and `OTEL_EXPORTER_OTLP_ENDPOINT` are read that
way here, and `OTEL_SDK_DISABLED` and `OTEL_RESOURCE_ATTRIBUTES` are read that
way in `Dash0DefaultConfigurationFileProvider`. `OTEL_CONFIG_FILE` is the one
exception, read from the system property and the environment variable alone,
which is what upstream does with it: `ConfigurationFile` is loaded by an
`AutoConfigurationCustomizerProvider`, and `maybeConfigureFromFile` reads
`otel.config.file` before any of those are loaded, so a path put in the
properties file reaches neither upstream nor this distribution. `DASH0_DISABLE` is compared against the string `true`
case-insensitively and after trimming, exactly as it is under environment
configuration, so `1`, `yes` and `on` still leave the SDK enabled.

A YAML file cannot express the trailing-slash stripping or the precedence rule,
and neither of them is lost: the customizer runs as plain Java after the file is
parsed, so it reads the environment itself.

The resource ordering follows from `ResourceFactory`, which starts from
`Resource.getDefault()`, applies the detectors under `detection/development`,
then `attributes_list`, then `attributes`, each overwriting the one before. So
the Dash0 attributes, written into `attributes`, beat every detector, which
reproduces the ordering `DistroResourceProvider.order()` of `200` gives today.
The host, process and service detectors are inserted ahead of every detector the
file names, so a detector the operator chose wins over them on any attribute
both would set.

A name the file declares in `attributes_list` counts as declared, exactly as one
it declares in `attributes` does. Both are read before anything is appended,
since `attributes` is applied over `attributes_list` and appending there would
discard what the operator wrote.

Where the two Dash0 sources collide, the distribution attributes win and the
package properties file loses, which is the ordering
`DistroResourceProvider.order()` and `Dash0PackageResourceProvider.order()`
produce under environment configuration. A package file naming
`telemetry.distro.name` reaches the exported resource in neither mode.

`OTEL_RESOURCE_ATTRIBUTES` is still ignored in this mode, by the SDK rather than
by this distribution. [Resource detection](resource-detection) gives the whole
list of what runs in this mode and what each detector contributes.

### The default configuration file

`Dash0DefaultConfigurationFileProvider` in `custom/` registers under
`DeclarativeConfigurationProvider`, the one SPI that runs early enough to
supply the file: `maybeConfigureFromFile` calls
`DeclarativeConfigUtil.configureFromSpi` before it reads `otel.config.file`,
and takes the first provider that answers with a model.

Running before `otel.config.file` is read is also what the provider has to
defend against. A provider that answered unconditionally would override an
`OTEL_CONFIG_FILE` an operator set, which is the reverse of the order above, so
the provider reads `otel.config.file` itself, from the system property first and
the environment variable second, and stands aside when it holds a value that is
not blank. That is the same pair of names, in the same order, that
`DefaultConfigProperties` would have given `maybeConfigureFromFile`. A path put
in an `otel.javaagent.configuration-file` properties file does not reach either,
upstream or here, because that file is read by an
`AutoConfigurationCustomizerProvider` and the early return happens first.

The file is parsed with `DeclarativeConfiguration.parse`, which is the call
`DeclarativeConfigUtil.configureFromFile` makes for the file `OTEL_CONFIG_FILE`
names, so `${VARIABLE}` substitution and the model are identical either way.

A default file that cannot be read or parsed stops autoconfiguration. The agent
prints `OpenTelemetry Javaagent failed to start` with the parse error under it,
the JVM then runs with no instrumentation, exports nothing and still exits zero.
There is no fallback to environment configuration, which is also what the Dash0
Python and Node.js distributions do with their own default file. A file that
parses to no document at all is treated the same way: `DeclarativeConfiguration.parse`
answers null rather than throwing for an empty file, a file of whitespace, a
file of comments and a bare `---`, which `touch` on the path, a truncated write
and a template that rendered nothing all produce, and the provider raises a
`ConfigurationException` naming the path.

A file that is valid YAML and then names an exporter, sampler or detector the
agent has no `ComponentProvider` for fails the same way one step later, in
`DeclarativeConfigUtil.create`, after `getConfigurationModel` has returned.
Under `OTEL_CONFIG_FILE` any of these mistakes reaches the one process whose
operator set the variable; under the default file it reaches every JVM on the
host, and the file belongs to a package rather than to the operator. Whatever
ships `/etc/opentelemetry/java/otel-config.yaml` is where it has to be validated
against the agent version the package carries.

Every other standard `OTEL_*` variable stops configuring the SDK directly on a
host that gains the default file, and the file is what answers for them, by
interpolating the ones you set. The file a Dash0 Linux package installs
interpolates `OTEL_EXPORTER_OTLP_ENDPOINT` on each of its exporters and
`OTEL_RESOURCE_ATTRIBUTES` as `resource.attributes_list`, among others, so both
of those keep applying, and so do the `k8s.*` attributes the Dash0 Kubernetes
operator carries through the second one. The substitution is
`DeclarativeConfiguration.parse`'s own, the same call the file `OTEL_CONFIG_FILE`
names goes through, which `substitutesAnEnvironmentReferenceTheDefaultFileMakes`
asserts. A variable the file does not interpolate has no effect, and the way to
restore it is to edit the file.

`OTEL_SERVICE_NAME` is not reported, and does not need to be. The `service`
detector the customizer inserts reads `otel.service.name` off the real
environment, so the variable survives the switch unless the file names
`service.name` itself. `theServiceDetectorMakesOtelServiceNameTakeEffect` and
`theFilesServiceNameBeatsOtelServiceName` in `DeclarativeConfigurationTest`
assert both halves of that.

### Dropping an attribute a detector contributes

`host`, `process` and `service` are inserted whenever the file does not name
them, so a file that leaves one out on purpose gets it anyway.
`OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS` is not the way back: it names
`ResourceProvider` classes, and no `ResourceProvider` is loaded in this mode. A
JVM started with
`OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS=io.opentelemetry.instrumentation.resources.HostResourceProvider`
and a configuration file exported `host.name` regardless.

The filter the file does have is `attributes` under
`resource.detection/development`, which takes an `included` and an `excluded`
list of glob patterns. `ResourceFactory` applies the resulting predicate to
every attribute name the detectors produce, the three inserted ones included:

```yaml
resource:
  detection/development:
    attributes:
      excluded:
        - host.*
        - process.*
```

A JVM started with that file exported `os.type`, `os.description`,
`os.version`, `service.instance.id`, `service.name` and the `telemetry.*`
attributes, and no name beginning with `host.` or `process.`.

The filter matches attribute names and not detectors. `os.type`,
`os.description` and `os.version` are contributed by the `host` detector, so
excluding `host.*` leaves all three in place, and dropping them as well takes
`os.*` in the list too.

It reaches detected attributes alone. `resource.attributes` and
`resource.attributes_list` are applied after the predicate has run, so the
Dash0 attributes and every name the file writes by hand survive any pattern.

The `process` detector of this mode contributes `process.pid`,
`process.executable.path` and the three `process.runtime.*` names. It
contributes no `process.command_args`, which is the name that carries a JVM's
command line. The environment path does contribute it, with the value of a
credential-shaped system property already replaced: a JVM started with
`-Dpassword=hunter2` exported `-Dpassword=***`.

### What the endpoint mapping does and does not touch

`DASH0_OTEL_COLLECTOR_BASE_URL` reaches an exporter node only when the file
declared that exporter and left its `endpoint` unset. The customizer walks
`tracer_provider.processors`, `meter_provider.readers` and
`logger_provider.processors`, and covers the `batch` and `simple` processors and
both the `otlp_http` and `otlp_grpc` exporters.

The endpoint mapping invents nothing. A file that declares no tracer provider
gets no processor and no exporter added to it, and a provider that declares no
processors gets none added. The point of `OTEL_CONFIG_FILE` is that the file is
the configuration, and a signal the operator did not ask to export is not
switched on by a host-wide variable.

The sampler and propagators defaults do attach a node, since a default has
nowhere else to go: `defaultTheSamplerToAlwaysOn` creates a `tracer_provider`
when the file declares none, and `defaultThePropagatorsToTracecontextBaggageXray`
creates a `propagator`. Neither adds a processor or an exporter, so neither
starts an export the file did not ask for.

Declare the exporter in the file and leave the `endpoint` out to have the
variable fill it in:

```yaml
file_format: "1.0"

tracer_provider:
  processors:
    - batch:
        exporter:
          otlp_http: {}
```

The `endpoint` of an `otlp_http` exporter is a full per-signal URL, so the
customizer appends `/v1/traces`, `/v1/metrics` and `/v1/logs` itself. The
`endpoint` of an `otlp_grpc` exporter carries no signal path and receives the
base URL unchanged.

A set `OTEL_EXPORTER_OTLP_ENDPOINT` suppresses the mapping. The file is left
exactly as it was, and `DASH0_OTEL_COLLECTOR_BASE_URL` is not applied anywhere.

Setting both variables on one workload sends its telemetry to neither host.
`OTEL_EXPORTER_OTLP_ENDPOINT` configures nothing in this mode, since the SDK
reads no `OTEL_EXPORTER_OTLP_*` variable from a declaratively configured
process, so an exporter the file left without an `endpoint` falls back to its
own specification default: `http://localhost:4318` for `otlp_http` and
`http://localhost:4317` for `otlp_grpc`. The stock upstream agent 2.33.1 does
the same with no Dash0 code involved, so this is the SDK's rule and not a
choice this distribution makes.

The customizer logs that at `WARNING`, naming the defaults the exporters keep.
It stays quiet when every exporter the file declares already names its
`endpoint`, and when the file declares no OTLP exporter at all, so a host that
sets `DASH0_OTEL_COLLECTOR_BASE_URL` for every workload does not warn the
workloads that are configured correctly. Name the endpoint in the file:

```yaml
tracer_provider:
  processors:
    - batch:
        exporter:
          otlp_http:
            endpoint: http://collector.internal:4318/v1/traces
```

### What the propagators default replaces

A file that declares no `propagator` node gets no propagation at all, not the
`tracecontext,baggage` the environment path would give it.
`OpenTelemetryConfigurationFactory` calls `OpenTelemetrySdkBuilder.setPropagators`
only when the parsed model carries a `propagator`, and the builder's own initial
value is `ContextPropagators.noop()`. An agent in that state writes no
`traceparent` on an outgoing request and reads none on an incoming one, so every
service it talks to starts a new trace.

The customizer writes `composite_list: tracecontext,baggage,xray`, the same
value `Dash0ConfigurationCustomizerProvider` supplies as `otel.propagators`. It
writes nothing when the file declares either a `composite` or a
`composite_list` that is not blank, so a file that asks for `b3multi` gets
`b3multi` alone.

A blank `composite_list` is the one case the default is written over.
`PropagatorFactory` drops every empty entry, so a blank value reaches the same
zero-propagator state as a missing `propagator` node, and
`composite_list: "${OTEL_PROPAGATORS}"` with the variable unset renders blank.

A file that wants no propagation at all has two spellings and the customizer
leaves both alone: `composite: []`, and `composite_list: none`, which
`PropagatorFactory` filters out the same way `OTEL_PROPAGATORS=none` is filtered
on the environment path.

`tracecontext` and `baggage` are built into `TextMapPropagatorFactory`. Every
other name in a `composite_list`, `xray` included, is resolved against a
registered `ComponentProvider`, and a name with no provider stops the SDK from
building. The upstream agent bundles
`io.opentelemetry.contrib.awsxray.propagator.internal.AwsXrayComponentProvider`,
which registers under the name `xray`, so the name resolves for the repackaged
agent jar and for the standalone extension jar alike.

### Naming the Dash0 contributions in a file of your own

The Dash0 Python, Ruby and Node.js distributions ship an `otel-config.yaml`
that names their detectors, with entries such as `- dash0_distribution: {}`
under `resource.detection/development`. A Java file cannot do that. This
distribution contributes its attributes through `resource.attributes` rather
than through a named detector, and registers no `ComponentProvider` under any
`dash0_` name.

A detector name the component loader cannot resolve is not ignored. The SDK
fails to build, the agent logs `OpenTelemetry Javaagent failed to start` with
the stack trace below, and the application then runs with no instrumentation
and exports nothing at all. The JVM itself exits zero, so a process in this
state looks healthy to everything watching it:

```
io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException: No component
provider detected for io.opentelemetry.sdk.resources.Resource with name
"dash0_distribution".
```

That is what agent 2.33.1 printed for a file whose
`resource.detection/development` named `dash0_distribution`. Write the file
with no `dash0_` entry and the Dash0 attributes still arrive, since the
customizer adds them to every parsed model.

### What a configuration file does not reach

Everything this distribution sets under environment configuration has a
counterpart in the table above, and the counterpart is the only reason it
arrives. `AutoConfigurationCustomizerProvider` and `ResourceProvider` are not
loaded from a declaratively configured process, so a setting registered through
either of those and nowhere else never runs in this mode. Whatever is added to
`Dash0ConfigurationCustomizerProvider` from now on has to be added to
`Dash0DeclarativeConfigurationCustomizerProvider` as well, or recorded here as
environment-only.

Two pull requests open against this repository are in that position today.
https://github.com/dash0hq/opentelemetry-java-distribution/pull/11 sets the MDC
capture defaults through `addPropertiesCustomizer`, and
https://github.com/dash0hq/opentelemetry-java-distribution/pull/12 sets
`otel.semconv-stability.opt-in` from the body of
`Dash0ConfigurationCustomizerProvider.customize`. Neither has merged. Each
registers under `AutoConfigurationCustomizerProvider` alone, so as written
neither would apply under `OTEL_CONFIG_FILE`.

The upstream resource providers are the other half. None of them is loaded
here, so the five `otel.resource.providers.<group>.enabled` toggles,
`otel.java.disabled.resource.providers` and
`otel.java.enabled.resource.providers` all do nothing, whichever way they are
set. [Resource detection](resource-detection) lists what does run.

### The file format warning

The agent logs this for a file declaring `file_format: "1.0"`:

```
Configuration file_format '1.0' does not exactly match expected version '1.2'.
```

Declaring `1.2` is not a fix, because the vendored `otel-config.yaml` upstream
ships for Java declares `1.0` as well.

### Where this is asserted

`DeclarativeConfigurationTest` in `smoke-tests/` launches a real JVM with
`OTEL_CONFIG_FILE` set and reads what it exports, for the repackaged agent jar
and for the standalone extension jar.
`Dash0DeclarativeConfigurationCustomizerProviderTest` in `custom/` runs the
customizer over a model it builds by hand and asserts on the result, which is
where the exporter shapes no fixture declares are covered.

`DefaultConfigurationFileTest` in `smoke-tests/` covers the three steps of the
order above, again in both delivery shapes. It installs the fixture at
`/etc/opentelemetry/java/otel-config.yaml` and removes it again. Where the
directory is not writable it skips on a developer machine and fails on CI,
which it tells apart by the `CI` variable every GitHub Actions runner sets, so
a workflow that stops preparing the directory turns the build red instead of
dropping the only end-to-end coverage the feature has.
`Dash0DefaultConfigurationFileProviderTest` in `custom/` points the same code at
a temporary directory, which is where the unreadable and unparseable files are
covered.

The path in step 2 is the default of the `dash0.default.configuration.file`
system property, and the build sets that property everywhere except in the JVMs
`DefaultConfigurationFileTest` launches, to a path under `build/` that nothing
creates. `custom/build.gradle.kts` sets it for the unit tests, and
`InstrumentedJvm` passes it to every JVM a smoke test launches unless the test
calls `readingTheHostDefaultConfigurationFile`.

Without it, a host that has the Dash0 Java package installed runs a different
test suite from a host that does not. The unit tests load the host's file into a
plain SDK, where the agent's `ComponentProvider` implementations are absent, and
`DefaultPropagatorsTest` fails with `No component provider detected for
io.opentelemetry.sdk.resources.Resource with name "process"`. The smoke tests
launch JVMs that take their configuration from the host's file instead of from
the environment each test sets up, which is the same hole that stripping `OTEL_*`
and `DASH0_*` from the launched environment exists to close. `gradle.properties`
sets `org.gradle.parallel = true`, so `:custom:test` can also meet the fixture
`DefaultConfigurationFileTest` installs while it is installed.

The declarative model classes are marked internal and experimental upstream, and
the path spelling `detection/development` is itself a version marker that will
change when the format stabilises. Upstream's own `javaagent-tooling` depends on
the same classes, so this distribution is no more exposed than the agent it is
built from, and a minor agent bump can break the compile.
