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

The repackaged agent jar is what Dash0 ships and supports; the extension jar is
a supported secondary artifact for users who keep the stock upstream agent. Do
not collapse this into one. Both are built from `:custom` unchanged, which is
what keeps the choice cheap to revisit.

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
   the Dash0 providers or every upstream provider disappears from the merged
   `META-INF/services` file. `AgentPackagingTest` compares that file against
   upstream's own copy rather than against a count written down here, so the
   expected set stays correct when upstream adds or removes a detector.
6. **`addPropertiesSupplier` instead of `addPropertiesCustomizer`.** Both are on
   `AutoConfigurationCustomizer` and both compile. A supplier provides defaults
   that an environment variable overrides; a customizer replaces what the
   environment set. `Dash0ConfigurationCustomizerProvider` needs the customizer,
   because `DASH0_OTEL_COLLECTOR_BASE_URL` has to beat
   `OTEL_EXPORTER_OTLP_ENDPOINT`. With the supplier it silently loses whenever
   both are set, which is the case that matters.
7. **The environment-variable name a customizer reads.** `ConfigProperties` sees
   normalised property names, so `DASH0_DISABLE` arrives as `dash0.disable`.
   Reading the wrong spelling returns null and the setting is ignored with no
   diagnostic.
8. **Locating a file beside the agent jar.** `Dash0PackageResourceProvider`
   resolves its properties file through the code source of its own class, which
   the agent loads from `inst/` as a `.classdata` entry rather than as an
   ordinary class file. When that resolution breaks the agent starts normally
   and the package attributes are simply absent.

The smoke tests in `smoke-tests/` exist specifically to catch all eight. They
assert on the shape of the built jars and on what a real instrumented JVM
exports. Treat a smoke-test failure as a real defect, never as a flaky test.

What guards what:

| Hazard | Check |
|---|---|
| 1, resource-provider ordering | `DistroResourceAttributesTest` |
| 2, relocation drift | `scripts/check-relocations.sh`, run by the `relocations` CI job |
| 2, 3 and 4, anything added to, dropped from or rewritten in the jar | `AgentCompositionTest` |
| 5, service-file merging | `AgentPackagingTest` |
| 6 and 7, the Dash0 settings | `Dash0ConfigurationTest`, and the `supported-jvms` matrix across Java 8 to 27 |
| 8, locating a file beside the jar | `PackageResourceAttributesTest` |
| Any of them, through the extension rather than the agent | `ExtensionRuntimeTest` |
| Export actually leaving the process | `examples/dash0-distro-java/verify.sh` |

Two habits these tests encode, both learned the hard way. Assert a presence
alongside every absence: a dead agent produces no telemetry and so satisfies
any assertion phrased as "does not contain", which is exactly how a provider
that stopped the agent from starting went unnoticed while its test passed.
And never let a resource provider throw, because SDK autoconfiguration aborts
and takes the whole agent with it.

Declarative configuration is a ninth hazard, of a different kind: setting
`OTEL_CONFIG_FILE` disables items 1, 6, 7 and 8 at once, because neither
`ResourceProvider` nor `AutoConfigurationCustomizerProvider` is consulted in
that mode. The measurement is in `docs/configuration.md`. Nothing in the test
suite covers it, because the distribution does not claim to support it yet.

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
existence, because none has been settled. The Python distribution's
rationale (removing native `protobuf`/`grpcio` dependencies so injection is
safe) **does not apply to Java**: the upstream agent is already a self-contained
shaded jar in its own class loader. Do not copy that reasoning across.
