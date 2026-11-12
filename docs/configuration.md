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

| Setting | Here | Upstream |
|---|---|---|
| `OTEL_TRACES_SAMPLER` | `always_on` | `parentbased_always_on` |
| `OTEL_INSTRUMENTATION_<APPENDER>_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED` | a deny list of credential-shaped key patterns | unset, which captures no MDC |

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
last section gives.

### MDC capture

MDC, the Mapped Diagnostic Context, is where an application puts the
identifiers people filter logs by: request id, tenant, customer, user, job id.
The upstream agent captures none of them, so those values reach Dash0 only as
a substring of the message body, if the log pattern includes them at all.

This distribution captures every MDC entry whose key does not look like a
credential, on all three appender instrumentations that read an MDC selector:

| Property | Appender |
|---|---|
| `otel.instrumentation.logback-appender.experimental.mdc-attributes.excluded` | Logback 1.0 and later |
| `otel.instrumentation.log4j-appender.experimental.mdc-attributes.excluded` | Log4j 2.17 and later, where MDC is called context data |
| `otel.instrumentation.jboss-logmanager.experimental.mdc-attributes.excluded` | JBoss Log Manager 1.1 and later, used by WildFly and JBoss EAP |

Each is set to the same twelve patterns:

```
*token*,*Token*,*TOKEN*,*secret*,*Secret*,*SECRET*,*password*,*Password*,*PASSWORD*,*authorization*,*Authorization*,*AUTHORIZATION*
```

No `.included` property is set. An excluded selector with no included selector
captures every key that does not match it, so this one value both turns capture
on and keeps the credential-shaped keys out.

The patterns are globs, where `*` matches any number of characters and `?`
matches exactly one. Matching is case-sensitive, which is why each stem appears
in three spellings: `*token*` on its own matches `session-token` and misses
both `authToken` and `AUTH_TOKEN`.

#### Changing what is captured

Setting any of an appender's three MDC properties on the workload replaces the
default for that appender and leaves the other two appenders alone:

```
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_INCLUDED
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES
```

All three feed one selector in the agent, and configuring any of them makes the
agent ignore the other two. A default excluded list that applied anyway would
turn an existing `CAPTURE_MDC_ATTRIBUTES` allow list into everything-not-denied
and capture more than its author asked for, so the distribution contributes its
value only when all three are absent or blank. An exported but empty variable
counts as absent, matching what the agent does with one.

Capture everything, including the credential-shaped keys:

```bash
export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_INCLUDED='*'
```

Capture nothing, which is the upstream behaviour:

```bash
export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED='*'
```

Capture a known set of keys and nothing else:

```bash
export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_INCLUDED='request.id,tenant.id,user.*'
```

Extend the deny list rather than replace it by restating the twelve patterns
above and appending to them. There is no property that adds to a default.

#### What the deny list does not do

It is a mitigation and not a guarantee. It matches four stems, so an MDC key
named `jwt`, `bearer`, `pwd`, `apiKey`, `sessionId`, `email` or `ssn` is
captured and exported like any other. Nothing inspects values, so a credential
stored under an innocuous key goes through whatever the key is called.

Treat the deny list as the floor and raise it where the application warrants
it, with an included list naming the keys you want, with Dash0's Signal Control
transformations, or by keeping the value out of MDC in the first place.

#### These are experimental property names

Every property on this page under `otel.instrumentation.*.experimental.*` is
marked experimental by upstream, which may rename or remove it in any minor
release with no deprecation period that the agent enforces. Recheck all nine
names against the appender READMEs of the new version whenever
`opentelemetryJavaagent` in `gradle/libs.versions.toml` is bumped. A name that
no longer exists is read by nothing and reports nothing: logs simply arrive
without MDC attributes.

Between agent 2.32.0 and 2.33.1 that happened to the setting this feature was
first specified against. At 2.32.0,
`otel.instrumentation.logback-appender.experimental.capture-mdc-attributes` was
a boolean defaulting to `false`. At 2.33.1 it is a deprecated comma-separated
list of literal MDC keys, so setting it to `true` captures an MDC key named
`true` and nothing else, with no warning. The `mdc-attributes.included` and
`mdc-attributes.excluded` pair replaced it, and is what this distribution sets.

Declarative configuration does not apply these defaults either, for the reason
the next section gives.

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
`DASH0_OTEL_COLLECTOR_BASE_URL`, the `always_on` sampler default and the MDC
capture defaults are silently ignored; a file that omits `sampler` gets the
specification's `parentbased_always_on`, and one that omits
`mdc_attributes/development` captures no MDC. Every upstream host, process and
OS detector disappears as well.

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
