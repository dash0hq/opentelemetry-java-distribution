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

A key that something else also sets is the one exception. Writing `service.name`
into the file alongside `OTEL_SERVICE_NAME=from-env` in the environment exports
`from-env`, and writing `host.name` into it exports the host detector's value
rather than the file's. This is deliberate rather than inherited: a value the
operator set on the workload should beat one the package baked in. It costs
nothing today because the two keys below are ones nothing else sets, and
`DistroResourceProvider` takes the opposite choice with an order of `200`
precisely because its job is to overwrite the agent's own value.

Two different mechanisms produce that, and the doc used to credit the wrong one
for both. Providers are applied in ascending `Ordered.order()` and later ones
win. `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` are read by
`EnvironmentResourceProvider`, which declares an order of `2147483646`, so the
environment is applied last whatever anything else does. The upstream detectors
declare no order at all, which means `0`; `Dash0PackageResourceProvider`
declares `Integer.MIN_VALUE`, so it is applied first and every detector
overwrites it. `ServiceInstanceIdResourceProvider` declares `Integer.MIN_VALUE`
too, so those two do tie; it contributes only `service.instance.id`, which no
Dash0 package writes. Leaving the Dash0 provider on the default `0` would tie
with the detectors, and a tie is settled by position in the merged `META-INF/services`
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

The table below is read off agent 2.33.1 by hand and nothing pins it to the jar.
`AgentPackagingTest` compares two jars of the same upstream version against each
other, so it stays green when a version bump adds, removes or renames a
provider. Re-derive the table from the service file whenever
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

Eleven of the 23 are off by default, and ten of those eleven are cloud
detectors. `HostIdResourceProvider` is the only non-cloud one. The five group
names and what each toggle does are in the resource-provider section of
[configuration](configuration).

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
