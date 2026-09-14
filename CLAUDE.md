# CLAUDE.md

Coding conventions and agentic guidelines for the Dash0 OpenTelemetry Java
distribution.

## Project overview

A Gradle multi-module build. All Dash0-authored code lives in `custom/`. The
module names are deliberately identical to upstream's
[`examples/distro`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/examples/distro),
because every vendor distribution uses them and it keeps upstream's build recipe
directly reusable.

Two artifacts are produced from the same `custom/` module:

| Module | Artifact | Loaded by |
|---|---|---|
| `:agent` | `dash0-opentelemetry-javaagent.jar` | `-javaagent:<jar>` |
| `:agentextension` | `dash0-opentelemetry-agentextension.jar` | the stock upstream agent, via `OTEL_JAVAAGENT_EXTENSIONS` |

Which one ships is an open question (OSS-41). Do not collapse this into one
until that is decided; keeping both is the whole point of the layout.

## Things that fail silently

This is the section to read before changing the build. The OpenTelemetry Java
agent is unusually unforgiving: the common mistakes do not produce an error, a
warning, or a stack trace. They produce an agent that starts normally and simply
does less than it should.

1. **Resource-provider ordering.** The agent ships its own `ResourceProvider`
   that sets `telemetry.distro.name`. Providers are applied in ascending
   `Ordered.order()` and later ones win. Without a higher order than upstream's,
   the Dash0 value is overwritten and all telemetry is attributed to the upstream
   agent. This was a real bug during scaffolding, caught only by asserting on
   what a real instrumented JVM exports.
2. **Package relocation.** `buildSrc/src/main/kotlin/Relocations.kt` is copied
   from upstream and must stay in sync with it. Note that resource providers are
   excluded from relocation because they live in the agent class loader. Relocate
   them and they are never invoked.
3. **The three class loader buckets.** Every dependency of `:agent` must be
   assigned to `bootstrapLibs`, `javaagentLibs`, or `upstreamAgent`. The wrong
   bucket gives `NoClassDefFoundError`, or two copies of a class where the wrong
   one wins.
4. **Bundling something the agent already has.** Amazon shipped a second copy of
   ASM at a version that could not read Java 25 class files, which disabled
   *all* instrumentation with no error. Dependencies in `custom/` are
   `compileOnly` for this reason.
5. **Service-file merging.** `DuplicatesStrategy` is set three different ways
   across the three packaging steps, each deliberately. Get it wrong and either
   the Dash0 provider or all 22 upstream providers disappear from the merged
   `META-INF/services` file.

The smoke tests in `smoke-tests/` exist specifically to catch all five. They
assert on the shape of the built jars and on the attributes a real instrumented
JVM exports. Treat a smoke-test failure as a real defect, never as a flaky test.

## Coding conventions

- Every function has a name that precisely describes what it does.
- Dependencies in `custom/` are `compileOnly`; the agent provides them at
  runtime.
- New SDK behaviour is an `@AutoService`-annotated SPI implementation in
  `custom/`, never a change to the packaging.
- Formatting is google-java-format via spotless. Run `./gradlew spotlessApply`.
- The build compiles with `-Werror`. Exactly two lint categories are disabled,
  each with its reason recorded in
  `buildSrc/src/main/kotlin/dash0.java-conventions.gradle.kts`. Do not add a
  third without recording why.
- Java 8 is the bytecode floor, because the upstream agent supports Java 8.

## Maintaining the documentation

`docs/` mirrors the structure of the Dash0 Python distribution's `docs/`, so the
two distributions document the same topics in the same order.

| Doc file | Authoritative source | What to check |
|---|---|---|
| `docs/overview.md` | `README.rst` | Description, architecture, the open decisions |
| `docs/getting-started.md` | `README.rst` (Trying it) | The runnable commands |
| `docs/configuration.md` | `custom/src/main/java/**` | Every `DASH0_*` variable and `OTEL_*` default the distro sets |
| `docs/resource-detection.md` | `custom/src/main/java/com/dash0/opentelemetry/DistroResource.java` | Attributes emitted and ordering behaviour |
| `docs/auto-instrumentation.md` | `gradle/libs.versions.toml` | The upstream agent version, which determines the instrumentation set |
| `docs/kubernetes-injection.md` | the Dash0 operator's `images/instrumentation/jvm` | How the agent is delivered and activated |

Update the relevant doc whenever a pull request changes its authoritative source.
Several of these files currently say "not implemented yet"; that is accurate and
should stay accurate rather than being filled in speculatively.

## What not to invent

The README deliberately does not claim a rationale for this distribution's
existence, because OSS-41 has not settled one. The Python distribution's
rationale (removing native `protobuf`/`grpcio` dependencies so injection is
safe) **does not apply to Java**: the upstream agent is already a self-contained
shaded jar in its own class loader. Do not copy that reasoning across.
