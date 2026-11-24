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

| Variable | This distribution | Upstream |
|---|---|---|
| `OTEL_TRACES_SAMPLER` | `always_on` | `parentbased_always_on` |
| `OTEL_PROPAGATORS` | `tracecontext,baggage,xray` | `tracecontext,baggage` |

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
mechanism that the next section describes.

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

Declarative configuration does not apply this default either.

## Declarative configuration

`OTEL_CONFIG_FILE` points the agent at a YAML file that replaces environment
configuration entirely. This distribution does not ship one, and works when one
is supplied. The variable is named `OTEL_CONFIG_FILE`;
`OTEL_EXPERIMENTAL_CONFIG_FILE` is read by nothing in agent 2.33.1.

The SDK consults a different pair of SPIs in this mode.
`AutoConfigurationCustomizerProvider` and `ResourceProvider`, which carry
everything this distribution contributes under environment configuration, are
never loaded: `AutoConfiguredOpenTelemetrySdkBuilder.buildImpl` returns from
`maybeConfigureFromFile` before it reaches either. The two that are loaded are
`DeclarativeConfigurationCustomizerProvider` and
`io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider`.

`Dash0DeclarativeConfigurationCustomizerProvider` in `custom/` registers under
the first of those. Its `addModelCustomizer` function receives the parsed
`OpenTelemetryConfigurationModel`, a mutable bean tree, and the SDK is built
from whatever it returns.

| Setting | What the customizer writes into the model |
|---|---|
| `DASH0_DISABLE=true` | `disabled: true`, which builds an SDK with no providers at all |
| `DASH0_OTEL_COLLECTOR_BASE_URL` | the `endpoint` of every OTLP exporter the file declared without one |
| the `always_on` sampler default | `tracer_provider.sampler`, unless the file declares a sampler |
| `telemetry.distro.name` and `telemetry.distro.version` | `resource.attributes`, unless the file declares that name |
| `dash0.package.*`, read from beside the agent jar | `resource.attributes`, unless the file declares that name |
| the upstream `host` and `process` detectors | `resource.detection/development`, unless the file names them |

Both Dash0 settings are read from the system property first and the environment
variable second, which is the precedence `DefaultConfigProperties` gives the
same two names under environment configuration. There is no `ConfigProperties`
instance in this mode. `DASH0_DISABLE` is compared against the string `true`
case-insensitively and after trimming, exactly as it is under environment
configuration, so `1`, `yes` and `on` still leave the SDK enabled.

A YAML file cannot express the trailing-slash stripping or the precedence rule,
and neither of them is lost: the customizer runs as plain Java after the file is
parsed, so it reads the environment itself.

The resource ordering follows from `ResourceFactory`, which starts from
`Resource.getDefault()`, applies the detectors under `detection/development`,
then `attributes_list`, then `attributes`, each overwriting the one before. So
the Dash0 attributes, written into `attributes`, beat every detector, which
reproduces the ordering `DistroResourceProvider.order()` gives today. The host
and process detectors are inserted ahead of every detector the file names, so a
detector the operator chose wins over them on any attribute both would set.

`OTEL_RESOURCE_ATTRIBUTES` is still ignored in this mode, by the SDK rather than
by this distribution. See `docs/resource-detection.md`.

### What the endpoint mapping does and does not touch

`DASH0_OTEL_COLLECTOR_BASE_URL` reaches an exporter node only when the file
declared that exporter and left its `endpoint` unset. The customizer walks
`tracer_provider.processors`, `meter_provider.readers` and
`logger_provider.processors`, and covers the `batch` and `simple` processors and
both the `otlp_http` and `otlp_grpc` exporters.

A file that declares no tracer provider gets no tracer provider invented for it,
and a provider that declares no processors gets none added. The point of
`OTEL_CONFIG_FILE` is that the file is the configuration, and a signal the
operator did not ask to export is not switched on by a host-wide variable.
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

`OTEL_EXPORTER_OTLP_ENDPOINT` still wins. When it is set, the file is left
exactly as it was, and `DASH0_OTEL_COLLECTOR_BASE_URL` is not applied anywhere.
The variable itself configures nothing in this mode, since the SDK reads no
`OTEL_*` variable from a declaratively configured process.

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

The declarative model classes are marked internal and experimental upstream, and
the path spelling `detection/development` is itself a version marker that will
change when the format stabilises. Upstream's own `javaagent-tooling` depends on
the same classes, so this distribution is no more exposed than the agent it is
built from, and a minor agent bump can break the compile.
