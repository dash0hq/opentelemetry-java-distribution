# Kubernetes injection

> **Nothing in this repository is wired into the Dash0 operator yet.** This page
> records how JVM workloads are instrumented today, so the integration point is
> clear when the OSS-41 decisions are made.

## How JVM workloads are instrumented today

The Dash0 operator uses the **stock upstream agent**, not this distribution. The
[operator's own notes](https://github.com/dash0hq/dash0-operator/blob/main/images/instrumentation/jvm/README.md)
state it plainly: "We currently do not have a Dash0 distro for Java. Rather, we
use the upstream OpenTelemetry Java agent."

The path is:

1. The agent version is pinned in `images/instrumentation/jvm/pom.xml` in the
   operator repository. That `pom.xml` builds nothing; it exists so Maven will
   download the jar during the Docker build.
2. The jar is baked into the Dash0 instrumentation image.
3. An init container copies it to a shared volume at
   `/__otel_auto_instrumentation/agents/jvm/opentelemetry-javaagent.jar`.
4. That path is declared to the [OpenTelemetry injector](https://github.com/open-telemetry/opentelemetry-injector)
   as `jvm_auto_instrumentation_agent_path`.
5. The operator sets `LD_PRELOAD` to the injector's shared library. At process
   start the injector appends `-javaagent:<path>` to `JAVA_TOOL_OPTIONS`.

Step 5 happens at process start rather than at pod-spec time because a
container's own Dockerfile or entrypoint script may set those variables, so their
final values are only knowable once the process is running.

## What integration would involve

Both delivery shapes fit this path, with different amounts of change:

- **Repackaged agent:** replace the jar the init container copies. The injector
  configuration is unchanged, because it already points at a single jar path.
- **Extension:** keep shipping the upstream jar, add the extension jar to the
  image, and set `OTEL_JAVAAGENT_EXTENSIONS` on the workload in addition to the
  existing variables.

## Resource attributes

The injector already sets `k8s.namespace.name`, `k8s.pod.name`, `k8s.pod.uid`
and `k8s.container.name` through `OTEL_RESOURCE_ATTRIBUTES` for every runtime, so
this distribution does not need its own Kubernetes detector to work under the
operator. See `docs/resource-detection.md`.
