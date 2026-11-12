# Resource detection

## Distribution attributes

`DistroResourceProvider` contributes:

| Attribute | Value |
|---|---|
| `telemetry.distro.name` | `dash0-java` |
| `telemetry.distro.version` | the version of this distribution |

The version is read at runtime from `dash0-distro-version.properties`, which the
Gradle `generateVersionResource` task writes into the jar, so it cannot drift
from the built artifact.

## Ordering, and why it matters

The upstream agent ships
`io.opentelemetry.javaagent.tooling.resources.DistroResourceProvider`, which sets
the same two attributes to `opentelemetry-java-instrumentation` and the jar
manifest version.

Resource providers are applied in ascending `Ordered.order()`, and later ones
win. `DistroResourceProvider.order()` therefore returns `100`, so the Dash0
values are applied after upstream's.

**This produces no error when it is wrong.** Without the ordering the agent
starts normally, the Dash0 provider runs, and its values are quietly discarded.
The unit tests still pass, because in isolation the provider returns exactly the
right resource. Only asserting on what a real instrumented JVM exports catches
it, which is what `DistroResourceAttributesTest` in `smoke-tests/` does.

## Package attributes

`Dash0PackageResourceProvider` contributes whatever attributes the thing that
installed the agent jar declares, by reading
`dash0-resource-attributes.properties` from the directory the agent jar sits in.
Every key in that file becomes a resource attribute whose value is the property
value. The file is absent unless a Linux package put it there, and its absence
is not an error.

A key that something else also sets is the one exception. Writing `service.name`
into the file alongside `OTEL_SERVICE_NAME=from-env` in the environment exports
`from-env`, and writing `host.name` into it exports the host detector's value
rather than the file's. This is deliberate rather than inherited: a value the
operator set on the workload should beat one the package baked in. It costs
nothing today because the two keys below are ones nothing else sets, and
`DistroResourceProvider` takes the opposite choice with an order of `100`
precisely because its job is to overwrite the agent's own value.

Two different mechanisms produce that, and the doc used to credit the wrong one
for both. Providers are applied in ascending `Ordered.order()` and later ones
win. `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` are read by
`EnvironmentResourceProvider`, which declares an order of `2147483646`, so the
environment is applied last whatever anything else does. Four other upstream
detectors declare an order of their own, `100` on the Spring Boot service-name
detector, `300` on `ManifestResourceProvider`, `1000` on
`JarServiceNameDetector` and `2147483646` on `HostIdResourceProvider`, while
the container, host, OS, process and process-runtime detectors leave it at the
default `0`. `Dash0PackageResourceProvider` declares `Integer.MIN_VALUE`, which
sorts below every one of them, so it is applied first and every detector
overwrites it. Leaving the Dash0 provider on the default `0` would tie with the
five that sit there, and a tie is settled by position in the merged
`META-INF/services`
file, which is a byproduct of the order `mergeServiceFiles` concatenates in and
could flip without failing anything.

The Dash0 Linux packages write exactly two keys:

| Attribute | Value |
|---|---|
| `dash0.package.name` | the package name, such as `dash0-java-autoinstrumentation` |
| `dash0.package.version` | the package version, which is not the distribution version |

The Dash0 Node.js, Python and Ruby packages report the same two attributes, but
they do it from the entry-point script the package owns, which appends them to
`OTEL_RESOURCE_ATTRIBUTES` before the SDK starts. Java has no entry-point
script: the OpenTelemetry injector contributes `-javaagent:` and nothing else,
so there is no process in which the package could set an environment variable.
Reading a file beside the jar is what replaces that.

This works in the repackaged agent jar only. The provider locates the file
through the code source of its own class, and that differs between the two
delivery shapes. In the agent jar the class is loaded from `inst/` as a
`.classdata` entry and reports a `file:` URL, so a directory can be resolved.
In the standalone extension jar it reports an `otel:` URL served by the agent's
own class loader, which no `FileSystemProvider` understands, so no directory
can be resolved and no package attributes are contributed. That costs nothing
in practice, because the Dash0 Linux packages install the agent jar.

`createResource` never throws. A resource provider that throws aborts SDK
autoconfiguration and takes the whole agent down, losing every signal including
the ones this distribution has nothing to do with. That is not hypothetical: an
earlier version of this provider called `Paths.get` on the `otel:` URL, and the
resulting `FileSystemNotFoundException` stopped the agent from starting at all
whenever the extension was used. That resolution is demonstrated by `PackageResourceAttributesTest`
in `smoke-tests/`, which copies the agent jar to a fresh directory, writes the
properties file beside it, and asserts on what the JVM exports. When the
resolution breaks the agent starts normally and the attributes are simply
absent, so the unit tests cannot catch it.

## Upstream detectors

All upstream resource providers remain active and unmodified: container, host,
OS, process, process runtime, manifest, jar service name, Spring Boot service
name and version, and the cloud providers that upstream ships disabled by
default. This distribution adds to that set rather than replacing it, and
`AgentPackagingTest` asserts that the merged service file still contains more
than just the Dash0 entry.

## Cloud detectors

Agent 2.33.1 registers ten cloud detectors and starts every one of them
disabled. This distribution leaves them disabled and does not plan to change
that. Turning a group on is one environment variable, documented below.

### The toggles

Four properties control the ten, read by
`io.opentelemetry.instrumentation.resources.internal.ResourceProviderPropertiesCustomizer`.
Each takes `true` or `false` and covers a whole group at once.

| Variable | System property | Detectors in the group |
|---|---|---|
| `OTEL_RESOURCE_PROVIDERS_AWS_ENABLED` | `otel.resource.providers.aws.enabled` | `BeanstalkResourceProvider`, `Ec2ResourceProvider`, `EcsResourceProvider`, `EksResourceProvider`, `LambdaResourceProvider` |
| `OTEL_RESOURCE_PROVIDERS_AZURE_ENABLED` | `otel.resource.providers.azure.enabled` | `AzureAppServiceResourceProvider`, `AzureFunctionsResourceProvider`, `AzureVmResourceProvider` |
| `OTEL_RESOURCE_PROVIDERS_GCP_ENABLED` | `otel.resource.providers.gcp.enabled` | `GCPResourceProvider` |
| `OTEL_RESOURCE_PROVIDERS_CLOUDFOUNDRY_ENABLED` | `otel.resource.providers.cloudfoundry.enabled` | `CloudFoundryResourceProvider` |

A fifth toggle of the same shape, `OTEL_RESOURCE_PROVIDERS_HOST_ID_ENABLED`,
covers `HostIdResourceProvider`, which reads `/etc/machine-id` on Linux and
reports `host.id`. It holds no cloud detector and is named here because it is
off by default through the same mechanism, which is easy to miss while looking
for a `host.id` that never arrives.

The customizer also lists `AzureAksResourceProvider` and
`AzureContainersResourceProvider` under the `azure` group. Agent 2.33.1
registers neither in its merged service file, so neither runs whatever the
toggle says.

`AgentPackagingTest` asserts that the ten names above are registered in the
agent jar, so an upstream bump that adds or drops one fails the build instead
of leaving this table wrong.

### What each detector contributes, and what it costs off its own cloud

| Detector | Group | Attributes | Cost when the process is elsewhere |
|---|---|---|---|
| `Ec2ResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.account.id`, `cloud.region`, `cloud.availability_zone`, `host.id`, `host.type`, `host.image_id`, `host.name` | two HTTP calls to `169.254.169.254`, 2 s timeout each |
| `AzureVmResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, `cloud.region`, `cloud.resource_id`, `host.id`, `host.name`, `host.type`, `os.type`, `os.version`, `azure.vm.scaleset.name`, `azure.vm.sku` | one HTTP call to `169.254.169.254`, 5 s timeout |
| `GCPResourceProvider` | `gcp` | `cloud.*` plus GCE, GKE, Cloud Run, Cloud Functions and App Engine specifics | one HTTP call to `metadata.google.internal`, no timeout set |
| `EksResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `k8s.cluster.name`, `container.id` | free off Kubernetes; inside any pod, one HTTPS call to `kubernetes.default.svc`, 2 s timeout |
| `EcsResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.account.id`, `cloud.region`, `cloud.availability_zone`, `cloud.resource_id`, `aws.ecs.*`, `aws.log.*`, `container.id`, `container.name`, `container.image.name`, `container.image.tags` | free, gated on `ECS_CONTAINER_METADATA_URI_V4` or `ECS_CONTAINER_METADATA_URI` |
| `BeanstalkResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `service.instance.id`, `service.version`, `service.namespace` | free, gated on `/var/elasticbeanstalk/xray/environment.conf` |
| `LambdaResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.region`, `faas.name`, `faas.version` | free, gated on `AWS_LAMBDA_FUNCTION_NAME` or `AWS_LAMBDA_FUNCTION_VERSION` |
| `AzureAppServiceResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, **`service.name`**, `cloud.region`, `cloud.account.id`, `cloud.resource_id`, `deployment.environment.name`, `host.id`, `service.instance.id`, `azure.app.service.stamp`, `azure.resource_group.name` | free, gated on `WEBSITE_SITE_NAME` |
| `AzureFunctionsResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, **`service.name`**, `faas.name`, `faas.version`, `faas.instance`, `faas.max_memory`, `cloud.region`, `cloud.account.id`, `deployment.environment.name`, `azure.resource_group.name` | free, gated on `WEBSITE_SITE_NAME` together with `FUNCTIONS_EXTENSION_VERSION` |
| `CloudFoundryResourceProvider` | `cloudfoundry` | `cloudfoundry.app.*`, `cloudfoundry.org.*`, `cloudfoundry.space.*`, `cloudfoundry.process.*` | free, gated on `VCAP_APPLICATION` |

Seven of the ten read an environment variable or a local file and stop. Three
open a socket: `Ec2ResourceProvider`, `AzureVmResourceProvider` and
`GCPResourceProvider`. `EksResourceProvider` is the awkward one, free on a host
and not free in a pod.

Both free Azure detectors set `cloud.provider` themselves, which the guard below
reads, so on an Azure App Service or Azure Functions host
`AzureVmResourceProvider` is skipped and the `azure` group costs nothing. On an
Azure VM it runs and answers at once. Anywhere else it runs and waits, and how
long it waits is the subject of the next two sections.

Whatever a detector costs is added to startup rather than paid in the
background. The SDK is autoconfigured from the agent's `premain`, and the
experiment below shows the consequence: a program whose `main` prints one line
and exits still waits out every timeout before printing it.

### How much that is, in an experiment

Linux 6.18.62, OpenJDK 21.0.12.1, agent 2.33.1, a program whose `main` prints
one line and exits. Nine runs per configuration, wall clock in seconds. On this
host `169.254.169.254` is routed to the default gateway, which drops the packets
silently, and `metadata.google.internal` does not resolve.

| Configuration | min | mean | max |
|---|---|---|---|
| no agent | 0.05 | 0.06 | 0.08 |
| agent, no group enabled | 2.14 | 2.77 | 3.60 |
| `cloudfoundry` | 2.61 | 3.02 | 3.76 |
| `host-id` | 2.62 | 3.33 | 4.39 |
| `gcp` | 2.57 | 3.02 | 3.52 |
| `aws` | 6.49 | 6.59 | 6.71 |
| `azure` | 7.32 | 7.55 | 7.69 |
| all five groups | 11.42 | 11.61 | 11.78 |

Compare the fastest run of each configuration against the fastest baseline run,
which is the statistic least affected by what else the machine was doing. `aws`
adds 4.35 s and `azure` adds 5.18 s, against declared timeouts of 2 s twice and
5 s once; the remainder is the class loading and the connection setup that
precede each wait. Naming
`io.opentelemetry.contrib.aws.resource.Ec2ResourceProvider` in
`OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS` returns `aws` to the baseline, and
naming `io.opentelemetry.contrib.azure.resource.AzureVmResourceProvider`
returns `azure` to it, which is how each figure is attributed to one provider.

`gcp` is cheap here only because the name does not resolve. Nothing guarantees
that elsewhere: `GcpMetadataConfig.fetchAttribute` opens an `HttpURLConnection`
and never calls `setConnectTimeout` or `setReadTimeout`, so the JDK default of
no timeout applies. Where `metadata.google.internal` resolves to an address that
accepts the connection and then answers nothing, the `premain` waits with no
bound on how long.

The run reported in issue 4 of this repository, on a macOS 14.6 host, costs
almost nothing. Reduced the same way, fastest run against fastest baseline run,
`aws` adds 0.13 s there, `azure` 0.09 s and `gcp` 0.03 s. Both sets of figures
are right. What decides the cost is what the network does with a packet
addressed to a metadata endpoint it has no route for: a host that answers with
an ICMP error or a TCP reset pays almost nothing, and a host that drops the
packet pays the full timeout. An operator shipping this distribution cannot know
in advance which kind of host it lands on.

### Why they stay off

A default is worth changing when the upstream default loses data that cannot be
recovered later. That is the case for `OTEL_TRACES_SAMPLER`, where an incoming
`traceparent` silently deletes spans that no downstream processing can bring
back, which is why `docs/configuration.md` documents a different default there.
Cloud attributes are not in that category. They can be supplied out of process,
once per host, by the Dash0 operator or by the `resourcedetection` processor in
a collector, and a workload that wants them from the JVM gets them by setting
one variable.

Against that stands a startup cost measured in seconds, paid by every JVM that
is not on the cloud in question, in an amount that depends on a network property
the distribution cannot observe. Four further reasons make enabling a group by
default worse than it looks.

Every detector except the Cloud Foundry one carries the same guard:

```java
public final boolean shouldApply(ConfigProperties config, Resource existing) {
  return existing.getAttribute(CLOUD_PROVIDER) == null;
}
```

The first detector to produce `cloud.provider` stops the rest. Among the ten,
only `AzureVmResourceProvider` declares an `order()`, of `100`. The other nine
leave it at `0`, and `Ordered` says of equal values that the implementations
"will be run in a non-deterministic order". In practice the sort is stable and
the order is the position in the merged `META-INF/services` file, which
`mergeServiceFiles` produces as a byproduct. Running the agent with `aws`
enabled against a stub instance metadata service and a stub ECS task metadata
endpoint, both answering, exports `cloud.platform="aws_ec2"` and not one ECS
task attribute, because `Ec2ResourceProvider` is listed above
`EcsResourceProvider`. Enabling one group makes that ordering this
distribution's problem. Enabling two makes it a race between groups.

`AzureAppServiceResourceProvider` and `AzureFunctionsResourceProvider` both set
`service.name` from `WEBSITE_SITE_NAME`, and both run at order `0`. The two
detectors that would otherwise decide the service name run later and decline to
act once something else has decided it: `SpringBootServiceNameDetector` at order
`100` and `JarServiceNameDetector` at order `1000` each apply only while the
accumulated `service.name` is still the SDK default `unknown_service:java`.
Enabling `azure` therefore renames every Azure App Service and Azure Functions
deployment that was getting its name from the Spring Boot application name or
from the jar file name. `OTEL_SERVICE_NAME` still beats it, because
`EnvironmentResourceProvider` declares an order of `2147483646`.
`CloudResourceDetectorsTest` in `smoke-tests/` asserts that the site name is
absent with the group off and present with it on.

The group toggles do not offer a "free detectors only" position. `aws` covers
`Ec2ResourceProvider` together with four free ones, and `azure` covers
`AzureVmResourceProvider` together with two. An operator reaches that split by
pairing the group toggle with `OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS`, which is
shown below.

Shipping it as a distribution default is harder than it looks. An
`addPropertiesSupplier` value is the thing an environment variable of the same
name overrides, so a workload that sets `OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS`
for a reason of its own would silently bring the probing detector back. An
`addPropertiesCustomizer` avoids that, by reading the workload's value and
returning the union, which is the shape `Dash0ConfigurationCustomizerProvider`
already uses for the OTLP endpoint. What it cannot do is tell apart a workload
that enabled `aws` without thinking about EC2 from one that enabled it wanting
EC2 detection, so the union would take `Ec2ResourceProvider` away from the
second of those, leaving `OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS` and its own
trap as the only way back. That is machinery to maintain for a group this
distribution has decided not to enable.

Leaving them off also keeps one sentence true, that this distribution enables
no detector upstream ships disabled. Every upstream troubleshooting document
about a cloud attribute that did not arrive therefore applies here unchanged,
and the answer to "why is `cloud.region` missing" is upstream's answer rather
than one of ours.

### Turning a group on

For a workload known to run on EC2:

```bash
export OTEL_RESOURCE_PROVIDERS_AWS_ENABLED=true
```

Or `-Dotel.resource.providers.aws.enabled=true` on the command line. The two
spellings reach the same setting: the SDK lowercases a property name and
replaces `-` with `.`, and lowercases an environment variable name and replaces
`_` with `.`, so both arrive as `otel.resource.providers.aws.enabled`.

To take the four free AWS detectors without the IMDS probe, name the one
provider to leave out:

```bash
export OTEL_RESOURCE_PROVIDERS_AWS_ENABLED=true
export OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS=io.opentelemetry.contrib.aws.resource.Ec2ResourceProvider
```

`io.opentelemetry.contrib.azure.resource.AzureVmResourceProvider` is the
equivalent name for the `azure` group. The customizer merges the value with the
list it builds itself, so nothing else is lost by setting it.

`OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS` is the opposite and is a trap here. It is
an allowlist over every resource provider rather than over the cloud ones, so
setting it to a cloud detector turns off every other entry in the merged service
file: the container, host, OS, process, jar service name and Spring Boot
detectors, both Dash0 providers, and `EnvironmentResourceProvider`.

Losing that last one is the expensive part, since it is what reads
`OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`. Running this agent with
`OTEL_SERVICE_NAME=checkout` exports `service.name="checkout"`. Adding
`OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS=io.opentelemetry.contrib.cloudfoundry.resources.CloudFoundryResourceProvider`
exports `service.name="unknown_service:java"` and no `telemetry.distro.name` at
all, with nothing logged about either.

## Not implemented

The Node.js and Python distributions ship a `k8s.pod.uid` detector and this one
does not.

The injector is not what distinguishes them. It sets `k8s.namespace.name`,
`k8s.pod.name`, `k8s.pod.uid` and `k8s.container.name` through
`OTEL_RESOURCE_ATTRIBUTES` for every runtime equally, Java included, which the
operator asserts in
`images/instrumentation/test/jvm/test-cases/otel-resource-attributes-unset/Main.java`.
If that covered the need, Python would not ship a detector either.

What the Python detector covers is the case where nothing sets those variables:
a JVM in a Kubernetes pod with neither the operator nor the injector. This
distribution leaves that case uncovered, deliberately. The work driving it is
the Linux system package, which installs on hosts rather than in pods, and under
the operator the injector already supplies the attributes. The gap is real and
is an inconsistency with Python rather than an oversight.

Declarative configuration is a second reason to revisit this. When
`OTEL_CONFIG_FILE` is set, `OTEL_RESOURCE_ATTRIBUTES` is ignored, so the
injector's attributes disappear for every runtime including Java. See the
measurement in `docs/configuration.md`.
