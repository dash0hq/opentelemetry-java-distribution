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
win. `DistroResourceProvider.order()` therefore returns `200`, so the Dash0
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

A key that an unguarded provider also sets is the one exception. Writing
`service.name` into the file alongside `OTEL_SERVICE_NAME=from-env` in the
environment exports `from-env`, and writing `host.name` into it exports the host
detector's value rather than the file's. Both of those providers write what they
have regardless of what is already there. This is deliberate rather than
inherited: a value the operator set on the workload should beat one the package
baked in.

The three `service.name` detectors in the table under `service.name` go the
other way, since each is guarded on the name still being `unknown_service:java`.
A `service.name` in the package file is already a name, so none of the three
contributes one: a JVM started with `-Dspring.application.name=my-spring-app`
beside a package file carrying `service.name=from-package` exports
`from-package`. In a `java -jar` launch whose manifest carries
`Implementation-Version`, `ManifestResourceProvider` still contributes
`service.version`, since `AttributeResourceProvider` tests each key on its own:
`service.name` against being `unknown_service:java`, and every other key against
being absent.

Losing a key to an unguarded provider costs nothing today because the two keys
below are ones nothing else sets, and `DistroResourceProvider` takes the
opposite choice with an order of `200` precisely because its job is to overwrite
the agent's own value.

The rule that the package file loses to an unguarded provider is inverted under
a configuration file, and the inversion is a consequence of the declarative
model rather than a second decision.
`Dash0DeclarativeConfigurationCustomizerProvider` writes every key of the file
into `resource.attributes`, which `ResourceFactory` applies after every
detector, and the declarative model offers no node that loses to a detector.
So the same install with `host.name=from-the-package-file` in
`dash0-resource-attributes.properties` exports the real host name under
environment configuration and `from-the-package-file` under a configuration
file.
`thePackageFileBeatsADetectorUnderTheConfigFile` in
`PackageResourceAttributesTest` holds that, beside
`losesEveryKeyAnUpstreamDetectorAlsoSets` which holds the environment rule.
Only the two `dash0.package.*` keys below are written by any Dash0 package
today, and nothing else sets either of them, so no package currently reaches
the difference.

Two different mechanisms produce the environment beating the package file and
the package file losing to every unguarded detector, and the doc used to credit
the wrong one for both. Providers are applied in ascending `Ordered.order()` and later ones
win. `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` are read by
`EnvironmentResourceProvider`, which declares an order of `2147483646`, the
highest value any registered provider declares, so the environment is applied
last. `HostIdResourceProvider` declares that same value and is the only provider
that ties with it, and the tie decides nothing: whichever of the two runs first,
a `host.id` carried by `OTEL_RESOURCE_ATTRIBUTES` is the one exported, since
`HostIdResourceProvider` declines while `host.id` is already set and is
overwritten while it is not. The container, host,
OS, process and process-runtime detectors declare no order at all, which means
`0`; `Dash0PackageResourceProvider` declares `Integer.MIN_VALUE`, so it is
applied first and every unguarded detector overwrites it.
`ServiceInstanceIdResourceProvider` declares `Integer.MIN_VALUE` too, so those
two do tie; it contributes only `service.instance.id`, which no Dash0 package
writes. Leaving the Dash0 provider on the default `0` would tie
with those detectors, and a tie is settled by position in the merged `META-INF/services`
file, which is a byproduct of the order `mergeServiceFiles` concatenates in and
could flip without failing anything. `0` is not the only value already taken.
Read the `order()` column of the table under `Upstream detectors` before
choosing one: it carries the value every one of the 23 declares, and several of
them declare something other than `0`.

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

Every resource provider the upstream agent registers is registered here and
unmodified, with the same group defaults upstream applies, so the eleven the
table below marks as off are off here too. This distribution adds to that set
rather than replacing it, and
`AgentPackagingTest` asserts that every provider in the upstream agent's own
service file survived the merge into ours.

The service file is
`inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider`
inside `agent/build/libs/dash0-opentelemetry-javaagent-*.jar`. At agent 2.33.1
it lists 25 providers: the two Dash0 ones above and the 23 upstream ones here.
The `.classdata` entries under `inst/` are ordinary class files, so `javap`
reads the `order()` values out of them once each entry is copied to a file whose
name ends in `.class`, which `javap` requires.

The table below is read off agent 2.33.1 by hand. Of the 23 providers it names,
only the ten cloud detectors are pinned to the jar, by the test the
`Cloud detectors` section below names. The comparison `AgentPackagingTest` makes
between two jars of the same upstream version stays green when a version bump
adds, removes or renames any other provider. Re-derive the table from the
service file whenever
`opentelemetryJavaagent` in `gradle/libs.versions.toml` moves. One rename has
already happened: `ServiceInstanceIdResourceProvider` was
`io.opentelemetry.sdk.extension.incubator.resources.ServiceInstanceIdResourceProvider`
at 2.32.0 and is `io.opentelemetry.sdk.autoconfigure.ServiceInstanceIdResourceProvider`
at 2.33.1, which matters to anything naming it in
`otel.java.disabled.resource.providers`.

| Provider | `order()` | Contributes | On by default |
|---|---|---|---|
| `ServiceInstanceIdResourceProvider` | `-2147483648` | `service.instance.id` | yes |
| `io.opentelemetry.javaagent.tooling.resources.DistroResourceProvider` | `0` | `telemetry.distro.name` and `telemetry.distro.version`, which the Dash0 provider then overwrites | yes |
| `ContainerResourceProvider` | `0` | `container.id` | yes |
| `HostResourceProvider` | `0` | `host.name`, `host.arch` | yes |
| `OsResourceProvider` | `0` | `os.type`, `os.description`, `os.version` | yes |
| `ProcessResourceProvider` | `0` | `process.pid`, `process.executable.path`, `process.command_args` | yes |
| `ProcessRuntimeResourceProvider` | `0` | `process.runtime.name`, `process.runtime.version`, `process.runtime.description` | yes |
| `SpringBootServiceVersionDetector` | `0` | `service.version` | yes |
| `SpringBootServiceNameDetector` | `100` | `service.name` | yes |
| `ManifestResourceProvider` | `300` | `service.name`, `service.version` | yes |
| `JarServiceNameDetector` | `1000` | `service.name` | yes |
| `HostIdResourceProvider` | `2147483646` | `host.id` | no, group `host-id` |
| `EnvironmentResourceProvider` | `2147483646` | whatever `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` carry | yes |
| `AzureAppServiceResourceProvider`, `AzureFunctionsResourceProvider` | `0` | Azure attributes, and `service.name` from `WEBSITE_SITE_NAME` | no, group `azure` |
| `AzureVmResourceProvider` | `100` | Azure attributes | no, group `azure` |
| `BeanstalkResourceProvider`, `Ec2ResourceProvider`, `EcsResourceProvider`, `EksResourceProvider`, `LambdaResourceProvider` | `0` | AWS attributes | no, group `aws` |
| `GCPResourceProvider` | `0` | Google Cloud attributes | no, group `gcp` |
| `CloudFoundryResourceProvider` | `0` | Cloud Foundry attributes | no, group `cloudfoundry` |

Several providers in the table are `ConditionalResourceProvider`s, and they do
not all apply the same condition. The three `service.name`
detectors write only while the name is still `unknown_service:java`, which the
`service.name` section below sets out in full. `ManifestResourceProvider` writes
its only other key, `service.version`, only while that key is absent.
`HostIdResourceProvider` writes `host.id` only while `host.id` is absent, so a
value a Dash0 package file or an enabled cloud detector already set survives it.
`SpringBootServiceVersionDetector` at order `0` and `EnvironmentResourceProvider`
at order `2147483646` declare no condition and overwrite whatever is there.

Reading the `order()` column alone therefore predicts the wrong winner wherever
one of those conditions applies. For `service.version`,
`SpringBootServiceVersionDetector` at order `0` beats `ManifestResourceProvider`
at order `300` whenever a `META-INF/build-info.properties` on the class path
carries a `build.version` property. A jar carrying both `build.version=1.0.0`
there and `Implementation-Version: 9.9.9` in its manifest exports
`service.version="1.0.0"`.

Eleven of the 23 are off by default, and ten of those eleven are cloud
detectors. `HostIdResourceProvider` is the only non-cloud one. The five group
names and what each toggle does are in the resource-provider section of
[configuration](configuration). The `Cloud detectors` section below gives what
each of the ten contributes, what it costs, and why this distribution leaves
every one of the four cloud groups off.

### `otel.java.enabled.resource.providers` is an allow-list

Two properties take a list of provider class names, and they are not symmetric:

| Property | Effect |
|---|---|
| `otel.java.disabled.resource.providers` | removes the providers named in it, and leaves every other one alone |
| `otel.java.enabled.resource.providers` | runs **only** the providers named in it, and removes all 24 others |

Reaching for `otel.java.enabled.resource.providers` to switch one detector on
therefore switches everything else off. Observed on agent 2.33.1, a JVM started
with

```bash
-Dotel.java.enabled.resource.providers=io.opentelemetry.instrumentation.resources.HostIdResourceProvider
```

exports `host.id`, plus the `service.name` and `telemetry.sdk.*` attributes that
`Resource.getDefault()` always carries, and nothing else. `host.name`, `os.type`,
`process.pid` and `telemetry.distro.name` are all gone, because
`DistroResourceProvider` is in the service file like everything else and the
allow-list does not name it. All telemetry from that process is attributed to
no distribution at all.

Naming a cloud detector in it costs the same way, and the expensive loss is
`EnvironmentResourceProvider`, which is what reads `OTEL_SERVICE_NAME` and
`OTEL_RESOURCE_ATTRIBUTES`. With `OTEL_SERVICE_NAME=checkout` alone this agent
exports `service.name="checkout"`. Adding
`OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS=io.opentelemetry.contrib.cloudfoundry.resources.CloudFoundryResourceProvider`
exports `service.name="unknown_service:java"` and no `telemetry.distro.name` at
all, with nothing logged about either.

Use `otel.resource.providers.<group>.enabled` to switch a disabled group on, and
`otel.java.disabled.resource.providers` to switch an individual provider off.

### `service.name`

Five providers can set `service.name` with the shipped defaults. Three of them are
`ConditionalResourceProvider`s guarded on the value still being
`unknown_service:java`, so the first one to find a name in ascending `order()`
is the one that wins:

| `order()` | Provider | Where the name comes from |
|---|---|---|
| `-2147483648` | `Dash0PackageResourceProvider` | a `service.name` key in `dash0-resource-attributes.properties`, which no Dash0 package writes |
| `100` | `SpringBootServiceNameDetector` | a `--spring.application.name=` argument in `sun.java.command`, the `spring.application.name` system property, `SPRING_APPLICATION_NAME`, then `application.properties`, `application.yml` and `application.yaml` in the working directory, then the same three on the class path, then `bootstrap.properties`, `bootstrap.yml` and `bootstrap.yaml` on the class path. There is no working-directory lookup for a `bootstrap` file. |
| `300` | `ManifestResourceProvider` | `Implementation-Title` in the main jar's manifest |
| `1000` | `JarServiceNameDetector` | the main jar's file name, without the `.jar` suffix |
| `2147483646` | `EnvironmentResourceProvider` | `OTEL_SERVICE_NAME`, or a `service.name` entry in `OTEL_RESOURCE_ATTRIBUTES` |

The three are `SpringBootServiceNameDetector`, `ManifestResourceProvider`, which
inherits the guard from `AttributeResourceProvider`, and `JarServiceNameDetector`.
The other two are plain `ResourceProvider`s and write whatever they have.
`Dash0PackageResourceProvider` runs first of everything, at
`Integer.MIN_VALUE`, so nothing has named the service yet when it writes, and
`EnvironmentResourceProvider` runs at `2147483646` and replaces whatever a
detector found rather than deferring to it.

Switching the `azure` group on adds two more. `AzureAppServiceResourceProvider`
and `AzureFunctionsResourceProvider` both set `service.name` from
`WEBSITE_SITE_NAME`, declare no order and so run at `0`, and gate on
`cloud.provider` being absent rather than on `service.name` still being
`unknown_service:java`. Both therefore run ahead of every detector in the table
except `Dash0PackageResourceProvider`, and a `spring.application.name` set on an
Azure App Service workload loses to `WEBSITE_SITE_NAME`.

These are the values a real instrumented JVM exported for each launch shape, all
reproduced on agent 2.33.1 and all covered by `ServiceNameDetectionTest` in
`smoke-tests/`:

| Launch | Resulting `service.name` |
|---|---|
| `-cp`, with nothing else set | `unknown_service:java` |
| `-Dspring.application.name=my-spring-app` | `my-spring-app` |
| `SPRING_APPLICATION_NAME=env-spring-app` | `env-spring-app` |
| an `application.properties` in the working directory carrying `spring.application.name=from-properties-file` | `from-properties-file` |
| `java -jar my-payments-service.jar` | `my-payments-service` |
| the same jar, with `Implementation-Title: titled-from-manifest` in its manifest | `titled-from-manifest` |
| the same jar, plus `-Dspring.application.name=my-spring-app` | `my-spring-app` |
| all of those, plus `OTEL_SERVICE_NAME=from-otel-service-name` | `from-otel-service-name` |

Two of those rows are worth knowing about.

`SpringBootServiceNameDetector` fires with no Spring anywhere on the class path.
It reads the system property, the environment variable and the files in the
working directory before it looks for Spring configuration on the class path, so
a plain `-cp` launch of an ordinary class picks up a `spring.application.name`
set for something else entirely.

`unknown_service:java` survives in a narrower set of cases than the name
suggests, and in a real one: a `-cp` launch with no Spring name and no
`OTEL_SERVICE_NAME`. Tomcat started through `catalina.sh`, the start scripts
Gradle and Maven generate, and a container image whose `CMD` is
`["java","-cp",…]` all land there. No shipped provider rescues that case, and
agent 2.33.1 has no servlet or WAR service-name detector. Set
`OTEL_SERVICE_NAME` on those workloads.

### Under a configuration file

Every provider named on this page, in the table of 23 and in the `service.name`
table alike, is loaded only under environment configuration. When a
configuration file configures the SDK, whether `OTEL_CONFIG_FILE` names it or it
is the default file at `/etc/opentelemetry/java/otel-config.yaml`, the SDK loads
no `ResourceProvider` at all, and the only detectors that run are the ones the file names under
`resource.detection/development`, the `host`, `process` and `service` detectors
the Dash0 customizer inserts, and `opentelemetry_javaagent_distribution`, which
the agent's own `ResourceCustomizerProvider` inserts whatever the file says.
That upstream detector contributes `telemetry.distro.name` and
`telemetry.distro.version`, and the Dash0 values in `resource.attributes` are
applied over it. A file that wants `container.id` has to name `container`
itself. The declarative configuration section of
[configuration](configuration) covers what else the customizer writes.

The detectors carry the same names in both modes and not the same attributes,
and three differences are worth knowing before a query is written against them.
The declarative `host` detector supplies `host.id` as well as `host.name`, so a
configuration file turns on the one attribute the environment path deliberately
leaves off, and there is no way to ask for `host.name` without it:
`ExperimentalHostResourceDetectorModel` has no fields. The declarative
`process` detector supplies no `process.command_args`, which the environment
path has, and nothing in this repository can add it.

The third difference is the one `OTEL_SERVICE_NAME` reaches.
`ServiceResourceDetector` builds a `DefaultConfigProperties` over the real
environment and reads `otel.service.name` from it, so naming the `service`
detector makes that one variable take effect on a process whose configuration
comes from a file. The stock upstream agent 2.33.1, which names no detector of
its own beyond `opentelemetry_javaagent_distribution`, exports
`unknown_service:java` for the same file and the same variable. The variable
still loses to a `service.name` the file writes in `resource.attributes`, which
is the reverse of the environment path, where `EnvironmentResourceProvider` at
order `2147483646` beats every other provider. `OTEL_RESOURCE_ATTRIBUTES` is
read by no detector at all and remains ignored in this mode.

## Cloud detectors

Agent 2.33.1 registers ten cloud detectors and starts every one of them
disabled. This distribution leaves them disabled and does not plan to change
that. Turning a group on is one environment variable: the four group names that
hold the ten, and the fifth that holds `HostIdResourceProvider` instead, are in
the resource-provider section of [configuration](configuration).

`ResourceProviderPropertiesCustomizer` also lists `AzureAksResourceProvider` and
`AzureContainersResourceProvider` under the `azure` group. Agent 2.33.1
registers neither in its merged service file, so neither runs whatever the
toggle says.

`registersTheTenCloudDetectorsThatResourceDetectionDocuments` in
`AgentPackagingTest` asserts that the ten the next table names, and no other
provider from the `io.opentelemetry.contrib` packages, are registered in the
agent jar. An upstream bump that adds, drops or renames one fails the build
instead of leaving the table wrong.

### What each detector contributes, and what it costs off its own cloud

| Detector | Group | Attributes | Cost when the process is elsewhere |
|---|---|---|---|
| `Ec2ResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.account.id`, `cloud.region`, `cloud.availability_zone`, `host.id`, `host.type`, `host.image.id`, `host.name` | two HTTP calls to `169.254.169.254`, 2 s timeout each |
| `AzureVmResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, `cloud.region`, `cloud.resource_id`, `host.id`, `host.name`, `host.type`, `os.type`, `os.version`, `azure.vm.scaleset.name`, `azure.vm.sku` | one HTTP call to `169.254.169.254`, 5 s timeout |
| `GCPResourceProvider` | `gcp` | `cloud.*` plus GCE, GKE, Cloud Run, Cloud Functions and App Engine specifics | one HTTP call to `metadata.google.internal`, no timeout set |
| `EksResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `k8s.cluster.name`, `container.id` | free off Kubernetes; inside a pod that mounts the default service account, one HTTPS call to `kubernetes.default.svc`, 2 s timeout |
| `EcsResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.account.id`, `cloud.region`, `cloud.availability_zone`, `cloud.resource_id`, `aws.ecs.*`, `aws.log.*`, `container.id`, `container.name`, `container.image.name`, `container.image.tags` | free, gated on `ECS_CONTAINER_METADATA_URI_V4` or `ECS_CONTAINER_METADATA_URI` |
| `BeanstalkResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `service.instance.id`, `service.version`, `service.namespace` | free, gated on `/var/elasticbeanstalk/xray/environment.conf` |
| `LambdaResourceProvider` | `aws` | `cloud.provider`, `cloud.platform`, `cloud.region`, `faas.name`, `faas.version` | free, gated on `AWS_LAMBDA_FUNCTION_NAME` or `AWS_LAMBDA_FUNCTION_VERSION` |
| `AzureAppServiceResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, **`service.name`**, `cloud.region`, `cloud.account.id`, `cloud.resource_id`, `deployment.environment.name`, `host.id`, `service.instance.id`, `azure.app.service.stamp`, `azure.resource_group.name` | free, gated on `WEBSITE_SITE_NAME` |
| `AzureFunctionsResourceProvider` | `azure` | `cloud.provider`, `cloud.platform`, **`service.name`**, `faas.name`, `faas.version`, `faas.instance`, `faas.max_memory`, `cloud.region`, `cloud.account.id`, `deployment.environment.name`, `azure.resource_group.name` | free, gated on `WEBSITE_SITE_NAME` together with `FUNCTIONS_EXTENSION_VERSION` |
| `CloudFoundryResourceProvider` | `cloudfoundry` | `cloudfoundry.app.*`, `cloudfoundry.org.*`, `cloudfoundry.space.*`, `cloudfoundry.process.*` | free, gated on `VCAP_APPLICATION` |

Six of the ten read an environment variable or a local file and stop. Three
open a socket: `Ec2ResourceProvider`, `AzureVmResourceProvider` and
`GCPResourceProvider`. `EksResourceProvider` is the awkward tenth, free on a
host and free in a pod that mounts no service account, since `EksResource.isK8s`
requires both the token and the CA certificate to exist before it calls
anything.

Both free Azure detectors set `cloud.provider` themselves, which the guard below
reads, so on an Azure App Service or Azure Functions host
`AzureVmResourceProvider` is skipped and the `azure` group costs nothing. On an
Azure VM it runs and answers at once. Anywhere else it runs and waits, and how
long it waits is the subject of the next section.

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
are right. What decides the cost is what happens to a connection opened to a
metadata endpoint that is not there. A host with no route to the address fails
the connect in its own kernel and pays nothing, which is the case the macOS run
reports. A host that routes the packet to something answering with an ICMP
error or a TCP reset pays almost nothing either. A host that routes it to
something which silently drops it pays the full timeout, which is the Linux
case above. An operator shipping this distribution cannot know in advance which
of the three it lands on.

### Why they stay off

A default is worth changing when the upstream default loses data that cannot be
recovered later and the workload has no way of telling that it is losing it.
Both of the defaults `docs/configuration.md` records meet both conditions. Under
`OTEL_TRACES_SAMPLER`, an incoming `traceparent` silently deletes spans that no
downstream processing can bring back, and nothing in the process reports that a
sampled flag arrived unset. Under `OTEL_PROPAGATORS`, a caller that sends only
`X-Amzn-Trace-Id`, such as an AWS service with X-Ray active tracing, leaves
`tracecontext,baggage` with no context to extract, so this JVM starts a second
trace that nothing downstream can join to the first, and from inside the process
that second trace looks like an ordinary one.

Cloud attributes fail the second condition, the one about the workload being
unable to tell. A missing `cloud.region` is a missing attribute, plain in the
exported resource to anyone who looks, and a workload that wants it knows which
cloud it runs on. For the `aws`, `azure` and `gcp` groups the attributes can
also be supplied out of process, once per host, by the Dash0 operator or by the
`resourcedetection` processor in a collector.

Cloud Foundry is the one group nothing outside the process can supply.
`VCAP_APPLICATION` exists only inside the application container, the Dash0
operator injects into Kubernetes pods and not into Cloud Foundry, and the
collector's `resourcedetection` processor ships no Cloud Foundry detector, so
`cloudfoundry.app.name` and its siblings are lost for as long as the group is
off. `CloudFoundryResourceProvider` is also free, is the only member of its
group, carries no `cloud.provider` guard and writes no `service.name`, so none
of the four reasons below reaches it. Two things keep it off. The second
condition fails here too: a workload on Cloud Foundry knows it is on Cloud
Foundry, the absent attributes are plain in the exported resource, and one
variable turns the group on. The upstream parity at the end of this section is
the other, and it reaches this group as much as it reaches the other nine
detectors.

For the `aws`, `azure` and `gcp` groups what enabling buys is therefore small.
What it costs is a startup penalty the experiment above puts in seconds for
`aws` and for `azure`, paid by every JVM that is not on the cloud in question,
in an amount that depends on a network property the distribution cannot
observe. The `gcp`, `cloudfoundry` and `host-id` groups each came in under half
a second in that same experiment. The `gcp` figure holds only where
`metadata.google.internal` does not resolve; where it resolves and then answers
nothing, the cost is the unbounded wait the section above describes. Four
further reasons make enabling a group by default worse than it looks.

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
`service.name` from `WEBSITE_SITE_NAME` and run ahead of the three detectors
that would otherwise decide it, which the `service.name` section above sets out
in full. Enabling `azure` therefore renames every Azure App Service and Azure
Functions deployment that was getting its name from the Spring Boot application
name, from `Implementation-Title` in the jar manifest, or from the jar file
name. `OTEL_SERVICE_NAME` still beats the site name, since
`EnvironmentResourceProvider` declares an order of `2147483646`.
`CloudResourceDetectorsTest` in `smoke-tests/` asserts that the site name is
absent with the group off, present with it on, and beaten by
`OTEL_SERVICE_NAME`.

The group toggles do not offer a "free detectors only" position. `aws` covers
`Ec2ResourceProvider` and `EksResourceProvider` together with three free ones,
and `azure` covers `AzureVmResourceProvider` together with two. An operator
reaches that split by
pairing the group toggle with `OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS`, which is
shown below.

Shipping it as a distribution default is harder than it looks. An
`addPropertiesSupplier` value is the thing an environment variable of the same
name overrides, so a workload that sets `OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS`
for a reason of its own would silently bring the probing detector back. An
`addPropertiesCustomizer` avoids that, by reading the workload's value and
returning the union. `Dash0ConfigurationCustomizerProvider` already registers
one, though what it does there with the OTLP endpoint is yield to a workload
that set its own rather than merge with it. What it cannot do is tell apart a
workload
that enabled `aws` without thinking about EC2 from one that enabled it wanting
EC2 detection, so the union would take `Ec2ResourceProvider` away from the
second of those with no way back. `ResourceConfiguration.configureResource`
checks the enabled list first and then skips any provider the disabled list
names, whatever the enabled list says, so `OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS`
does not undo a name the union put in `OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS`.
That is machinery to maintain for a group this distribution has decided not to
enable.

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

To take the three free AWS detectors without the IMDS probe, name the one
provider to leave out:

```bash
export OTEL_RESOURCE_PROVIDERS_AWS_ENABLED=true
export OTEL_JAVA_DISABLED_RESOURCE_PROVIDERS=io.opentelemetry.contrib.aws.resource.Ec2ResourceProvider
```

`EksResourceProvider` is the fourth AWS detector that is not the IMDS probe, and
it is free only outside a pod. In any pod that mounts the default service
account, `EksResource` finds
`/var/run/secrets/kubernetes.io/serviceaccount/token` and its CA certificate and
issues an HTTPS GET to
`https://kubernetes.default.svc/api/v1/namespaces/kube-system/configmaps/aws-auth`
with a 2 s timeout. A workload that runs in Kubernetes and is not on EKS adds
`io.opentelemetry.contrib.aws.resource.EksResourceProvider` to the same list.

`io.opentelemetry.contrib.azure.resource.AzureVmResourceProvider` is the
equivalent name for the `azure` group. `ResourceProviderPropertiesCustomizer`
merges the value with the list it builds itself, so nothing else is lost by
setting it.

`OTEL_JAVA_ENABLED_RESOURCE_PROVIDERS` is the opposite, and naming a cloud
detector in it switches off every other provider in the merged service file.
The `otel.java.enabled.resource.providers` section above has what a JVM exports
once that happens.

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

Declarative configuration is a second reason to revisit this. Under a
configuration file, `OTEL_RESOURCE_ATTRIBUTES` is ignored by the SDK, so the
injector's attributes disappear for every runtime including Java. The Dash0
customizer described in [configuration](configuration) restores the Dash0
attributes and the host, process and service detectors in that mode, and does
not restore `OTEL_RESOURCE_ATTRIBUTES`.
