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

## Upstream detectors

All upstream resource providers remain active and unmodified: container, host,
OS, process, process runtime, manifest, jar service name, Spring Boot service
name and version, and the cloud providers that upstream ships disabled by
default. This distribution adds to that set rather than replacing it, and
`AgentPackagingTest` asserts that the merged service file still contains more
than just the Dash0 entry.

## Not implemented

The Node.js and Python distributions ship a `k8s.pod.uid` detector. Java has no
equivalent here, because the Dash0 operator's OpenTelemetry injector already
supplies `k8s.namespace.name`, `k8s.pod.name`, `k8s.pod.uid` and
`k8s.container.name` through `OTEL_RESOURCE_ATTRIBUTES` for every runtime.
Whether Java needs its own detector for non-injected deployments is an open
question.
