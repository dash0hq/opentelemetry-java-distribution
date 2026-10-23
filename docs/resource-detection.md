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

The provider locates the file through the code source of its own class, which
the agent loads from `inst/` as a `.classdata` entry rather than as an ordinary
class file. That resolution is demonstrated by `PackageResourceAttributesTest`
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
