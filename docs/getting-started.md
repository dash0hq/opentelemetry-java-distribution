# Getting started

## Supported JVMs

This distribution supports every JVM the upstream agent supports, and commits to
nothing beyond that. Upstream states Java 8 as the minimum and runs its
integration tests against OpenJDK and OpenJ9 at versions 8, 11, 17, 21, 25 and
27 on `ubuntu-latest`:
https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md#jvms-and-operating-systems

The distribution's own classes are compiled with `options.release.set(8)` in
`buildSrc/src/main/kotlin/dash0.java-conventions.gradle.kts`, so they do not
raise that floor. Everything this distribution adds is a resource provider and
an autoconfiguration customizer, neither of which touches bytecode
manipulation, so there is no mechanism by which it could support fewer JVMs
than the agent it repackages.

The support floor therefore moves when upstream's does, with the agent version
bump that moves it, and is not tracked separately here.

## Build

```bash
./gradlew build
```

This compiles, runs the unit tests, assembles both jars, and runs the smoke
tests, which include launching a real JVM with the agent attached.

Artifacts:

- `agent/build/libs/dash0-opentelemetry-javaagent-<version>.jar`
- `agentextension/build/libs/dash0-opentelemetry-agentextension-<version>.jar`

## Run with the repackaged agent

```bash
java -javaagent:agent/build/libs/dash0-opentelemetry-javaagent-0.1.0-SNAPSHOT.jar \
  -Dotel.service.name=my-service \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -jar my-application.jar
```

## Run the same code as an extension on the stock agent

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=agentextension/build/libs/dash0-opentelemetry-agentextension-0.1.0-SNAPSHOT.jar \
  -Dotel.service.name=my-service \
  -jar my-application.jar
```

## Confirming it is active

Export metrics to the console and look at the resource attributes:

```bash
java -javaagent:agent/build/libs/dash0-opentelemetry-javaagent-0.1.0-SNAPSHOT.jar \
  -Dotel.traces.exporter=none -Dotel.logs.exporter=none \
  -Dotel.metrics.exporter=logging -Dotel.metric.export.interval=1000 \
  -Dotel.service.name=my-service \
  -jar my-application.jar
```

`telemetry.distro.name="dash0-java"` in the printed resource means the
distribution is in effect. If it reads `opentelemetry-java-instrumentation`, the
Dash0 resource provider is being overridden by the agent's own; see
`docs/resource-detection.md`.
