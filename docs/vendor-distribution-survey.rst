============================
Vendor distribution survey
============================

:Surveyed: 2026-09-24
:Tracking: `OSS-41 <https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro>`_

.. note::

   This document records **findings, not decisions**. It is the evidence behind
   `decisions/0001-distribution-approach.rst
   <decisions/0001-distribution-approach.rst>`_, which is where the choices this
   repository actually made are written down, and where they should be argued
   with.

   It is a snapshot. The repositories it describes keep moving, so treat the
   version numbers and file paths below as evidence of what was true when it was
   written rather than as current fact.

Scope and method
================

OSS-41 asks three questions about vendor Java distros: **what** they change
with respect to upstream, **how** they make those changes, and **how their
build processes work**. The premise in the issue is that the OpenTelemetry Java
agent is "rather rail-roaded" in how it expects distros to be created.

The premise is correct, and this document quantifies it.

The analysis is based on reading the source of the distros, not their
documentation. The following repositories were cloned at ``main`` and their
Gradle build logic read directly:

* ``open-telemetry/opentelemetry-java-instrumentation`` (the upstream template)
* ``elastic/elastic-otel-java``
* ``signalfx/splunk-otel-java``
* ``grafana/grafana-opentelemetry-java``
* ``aws-observability/aws-otel-java-instrumentation``
* ``microsoft/ApplicationInsights-Java``
* ``honeycombio/honeycomb-opentelemetry-java`` (archived)
* ``getsentry/sentry-java``
* ``dash0hq/dash0-operator`` (for the current Dash0 baseline)

Where a claim below cites a path, the path is relative to the root of the named
repository.

Baseline: where Dash0 is today
==============================

The Dash0 operator ships no Java distro. Its own
``images/instrumentation/jvm/README.md`` states this plainly:

    We currently do not have a Dash0 distro for Java. Rather, we use the
    upstream OpenTelemetry Java agent.

The mechanism is a Maven POM used purely as a downloader.
``images/instrumentation/jvm/pom.xml`` declares a single dependency,
``io.opentelemetry.javaagent:opentelemetry-javaagent`` pinned at **2.31.1**, and
the Dockerfile runs ``./mvnw dependency:copy-dependencies`` to drop
``opentelemetry-javaagent.jar`` into the instrumentation image at
``/dash0-instrumentation/agents/jvm/``.

Two consequences matter for this analysis:

1. The integration point is **a single agent jar on a mounted volume**,
   referenced from ``JAVA_TOOL_OPTIONS``. Whatever we build has to be
   deliverable as one file in that image.
2. There is no code of ours in the Java path today, so every behaviour Dash0
   wants must currently be expressed as environment variables set by the
   operator.

How upstream expects a distro to be built
=========================================

Upstream ships a template at ``examples/distro`` in
``opentelemetry-java-instrumentation``. Every vendor distro examined here is a
copy of it, in several cases copied verbatim down to the comments.

The template mandates a specific three-configuration, three-task shape, defined
in ``examples/distro/buildSrc/src/main/kotlin/otel.javaagent-shadow-conventions.gradle.kts``.

The three Gradle configurations
-------------------------------

``bootstrapLibs``
    Code that must land in the **bootstrap classloader**, at the root of the
    jar. Visible to the instrumented application.

``javaagentLibs``
    Distro code that must land in the **agent classloader**, isolated from the
    application.

``upstreamAgent``
    The upstream ``opentelemetry-javaagent`` artifact being extended.

The three build steps
---------------------

1. **Relocate.** A ``ShadowJar`` task rewrites the distro's own code so that
   every reference to ``io.opentelemetry.api``, ``io.opentelemetry.context``,
   ``io.opentelemetry.semconv`` and ``io.opentelemetry.instrumentation`` points
   at the ``io.opentelemetry.javaagent.shaded.*`` equivalents, and rewrites
   ``java.util.logging.Logger`` to the agent's ``PatchLogger``. Certain
   artifacts (``opentelemetry-api``, ``opentelemetry-context``, the semconv
   jars) must be *excluded* here because they belong in the bootstrap
   classloader and cannot appear under ``inst/``.

2. **Isolate.** A ``Copy`` task moves everything into an ``inst/`` prefix and
   renames every ``*.class`` file to ``*.classdata``. The rename is what stops
   the JVM from loading these classes through normal classpath scanning; the
   agent's own classloader undoes it at runtime. The template carries a comment
   explaining that this must be a separate task because a ``CopySpec`` carries
   its own ``duplicatesStrategy``, so doing it inline would not deduplicate
   against upstream.

3. **Merge.** The final ``ShadowJar`` merges the isolated distro code, the
   bootstrap libs and the upstream agent jar with
   ``duplicatesStrategy = EXCLUDE``, merges ``inst/META-INF/services`` service
   files, and writes a manifest with ``Premain-Class``, ``Agent-Class``,
   ``Can-Redefine-Classes`` and ``Can-Retransform-Classes``.

Why this feels rail-roaded
--------------------------

The constraint is real and it is not merely stylistic:

* The relocation block is **load-bearing and non-obvious**. Getting it wrong
  produces a jar that builds cleanly and fails at runtime, because the distro's
  code references class names that do not exist inside the agent.
* The set of artifacts that must be excluded from ``inst/`` is a hardcoded list
  that tracks upstream's internal packaging decisions. It changes over time.
* Roughly 150 lines of Gradle must be copied into each distro and then kept in
  sync with upstream by hand. There is no published plugin that encapsulates
  it. Sentry's
  ``sentry-opentelemetry/sentry-opentelemetry-agent/build.gradle.kts`` and
  Grafana's ``agent/build.gradle`` are near-identical copies of the template,
  including its comments, which is direct evidence that the ecosystem
  copy-pastes rather than abstracts.
* The supported extension points are SPI-shaped and additive. There is **no
  supported way to replace or modify an upstream class**. As shown below, every
  vendor that needed to do so had to leave the supported path entirely.

The six techniques vendors actually use
=======================================

Ordered from cheapest to most expensive to maintain.

1. Extension jar, no repackaging
--------------------------------

The agent can load a plain fat jar through ``otel.javaagent.extensions`` /
``OTEL_JAVAAGENT_EXTENSIONS``, and it can also load extensions **embedded inside
the agent jar** under an ``extensions/`` entry. Both paths are implemented in
``javaagent-tooling/src/main/java/io/opentelemetry/javaagent/tooling/ExtensionClassLoader.java``:
``includeEmbeddedExtensionsIfFound`` scans the agent jar for the ``extensions/``
prefix, and ``parseLocation`` handles the configured paths.

The decisive detail is in ``addFileUrl``: if the agent detects it is running
shaded, it wraps the extension jar in a ``RemappingUrlStreamHandler``. **The
agent rewrites extension classes at load time.** An extension is therefore
written against ordinary, unrelocated ``io.opentelemetry.*`` names and needs
none of the relocation boilerplate. ``examples/extension/build.gradle.kts``
confirms this: it declares the OpenTelemetry APIs ``compileOnly`` and applies
no ``relocate`` calls at all.

This is the mechanism upstream now recommends for most users.

Its limits: extension code cannot add classes to the bootstrap classloader,
cannot override upstream classes, and the resulting artifact is not a
self-contained agent jar unless it is embedded.

2. Template distro, configuration only
--------------------------------------

Copy the template, add one ``custom`` module implementing
``AutoConfigurationCustomizerProvider``, keep upstream's entry point. Grafana is
the reference case.

3. Template distro plus own instrumentation and entry point
-----------------------------------------------------------

As above, plus vendor-authored ``InstrumentationModule`` implementations and a
custom ``Premain-Class`` that delegates to upstream's. Splunk, Sentry, AWS and
Azure all do this.

The custom entry point is consistently a thin delegator. Splunk's
``SplunkAgent`` simply calls ``OpenTelemetryAgent.agentmain``. Elastic's
``ElasticAgent`` sets a logging system property, then delegates. The value is
having a branded class name in stack traces and a hook that runs before
upstream's ``premain``.

4. Package-private access via same-package classes
---------------------------------------------------

Where a vendor needs access to something upstream did not make public, they
place a source file into the upstream package so it can reach package-private
members. This is unsupported but common:

* ``splunk-otel-java/custom/src/main/java/io/opentelemetry/sdk/autoconfigure/AutoConfigureUtil.java``
* ``elastic-otel-java/common/src/main/java/io/opentelemetry/sdk/trace/FieldBackedSpanValueStorageProvider.java``

Both repositories are otherwise disciplined about staying in their own
namespace. These are exactly the two places where the supported API ran out.

5. Bytecode surgery on upstream classes at packaging time
----------------------------------------------------------

Two variants observed, both operating on the already-assembled jar.

**Elastic: inject a field into an upstream class.**
``elastic-otel-java/buildSrc/src/main/kotlin/elastic-otel.agent-packaging-conventions.gradle.kts``
registers a custom Shadow ``ResourceTransformer`` that matches exactly
``inst/io/opentelemetry/sdk/trace/SdkSpan.classdata``, runs it through ASM, and
appends a ``volatile`` field named ``$elasticSpanValues`` of type ``Object``.
This gives them O(1) storage for per-span vendor state instead of a map
lookup. The transformer throws if the class is not found or is found twice, so
an upstream refactor that moves ``SdkSpan`` fails the build loudly rather than
silently disabling the feature. That is careful engineering, and it is also a
standing maintenance liability.

**Azure: exclude upstream classes, then deduplicate.**
``ApplicationInsights-Java/agent/agent/build.gradle.kts`` builds an intermediate
``shadowJarWithDuplicates`` carrying the classifier ``dontuse``, excluding
``inst/io/prometheus/**``, ``inst/zipkin/**`` and upstream's ``slf4j-simple``
binding, then runs a second ``shadowJar`` over that jar purely to collapse
duplicates. They also note in a comment that excluding the slf4j service file
also removes their own, which they "hackily re-add" from
``agent/agent/src/main/resources``.

6. Patch upstream source and rebuild it
----------------------------------------

The heaviest technique, used by AWS for their Lambda layer.
``aws-otel-java-instrumentation/lambda-layer/build-upstream-deps.sh`` clones
``opentelemetry-java-instrumentation`` at a release tag, applies
``lambda-layer/patches/opentelemetry-java-instrumentation.patch`` (and a
separate patch to ``opentelemetry-java-contrib``), runs
``./gradlew publishToMavenLocal``, and then builds the layer against the
resulting local artifacts. A companion patch bumps their own version string to
``2.31.1-adot-lambda1`` so the patched build is distinguishable.

This is a full source fork managed as a patch queue. It gives unlimited power
and inherits a rebase cost on every upstream release.

Vendor-by-vendor findings
=========================

Elastic (``elastic/elastic-otel-java``)
---------------------------------------

The most heavily engineered distro examined, and the most instructive.

**Delta vs upstream.** Inferred spans (sampling profiler derived spans), a
universal profiling integration, dynamic central configuration over OpAMP,
a composite sampler, legacy Elastic APM configuration option translation,
OpenAI client instrumentation, a span stacktrace feature, and their own
internal logging stack based on log4j2.

**How.** Module layout mirrors the upstream template exactly (``agent``,
``bootstrap``, ``custom``, ``instrumentation``, ``smoke-tests``,
``testing:agent-for-testing``) and then adds ``jvmti-access``,
``universal-profiling-integration``, ``inferred-spans``, ``runtime-attach``,
``internal-logging``, ``resources``, ``common`` and ``agentextension``.

``jvmti-access`` contains real JNI C++ (``ElasticJvmtiAgent.cpp``,
``JvmtiAccessImpl.cpp``, ``ProfilerSocket.cpp``, ``VirtualThreadSupport.cpp``)
with a Docker-based cross-compilation setup under ``jni-build``. A native
library shipped inside the agent jar is a significant escalation in build and
supply-chain complexity.

They define their own extension point, ``ChainingSpanProcessorAutoConfiguration``
in ``common/``, so that internal features can compose span processors without
each one having to win a fight over ``addSpanProcessorCustomizer``.

They replace upstream's logging: the shadow step excludes
``inst/io/opentelemetry/javaagent/slf4j/simple/**`` and ``ElasticAgent.premain``
sets ``otel.javaagent.logging=elastic`` unless the user set it explicitly.

**The dual-artifact pattern.** This is the single most useful finding for
Dash0. ``agentextension/build.gradle.kts`` packages the very same ``:custom``
project as a plain fat jar, described in the build file as bundling the
extensions "to be used with the vanilla agent via the ``otel.javaagent.extensions``
config option". No relocation, no ``inst/``, no ``classdata``. Elastic ships one
body of code as **both** a full distro and a drop-in extension, and the
extension packaging is about 20 lines of Gradle.

**Build and upstream sync.** Pinned to a released upstream version
(``opentelemetryJavaagentAlpha = "2.31.1-alpha"`` in
``gradle/libs.versions.toml``). A daily ``updatecli`` cron
(``.github/workflows/updatecli.yml``, ``0 6 * * *``) opens bump PRs, and
``.ci/update-upstream.sh`` scrapes upstream's ``version.gradle.kts`` and
``dependencyManagement/build.gradle.kts`` with ``curl`` and ``sed`` to derive
the agent, contrib and semconv versions. Renovate is explicitly *disabled* for
those coordinates to avoid fighting the script. Release is a multi-step
workflow (``release-step-1.yml``, ``release-step-3.yml``) with signing and a
changelog assembled from fragments.

Splunk (``signalfx/splunk-otel-java``)
--------------------------------------

**Delta vs upstream.** The largest set of vendor-authored instrumentation of
any distro here: ``glassfish``, ``jdbc``, ``jetty``, ``jvm-metrics``, ``khttp``,
``liberty``, ``nocode``, ``tomcat``, ``tomee``, ``weblogic``, ``websphere``,
``wildfly``. Plus AlwaysOn Profiling (``profiler/``, JFR-based:
``JfrRecorder.java``, ``JfrEvent.java``), an OpAMP module, a metadata generator
and a compatibility test matrix.

**How.** Standard template, 15 ``@AutoService(InstrumentationModule)``
registrations, a ``SplunkAgent`` entry point, and one package-private access
class. Manifest ``Implementation-Version`` is
``splunk-${project.version}-otel-$otelInstrumentationVersion``, which encodes
both versions in a single string.

**Build and upstream sync.** The notable difference: Splunk tracks upstream
**snapshots**, not releases. ``dependencyManagement/build.gradle.kts`` pins
``otelInstrumentationVersion = "2.32.0-SNAPSHOT"`` and the build resolves from
``https://central.sonatype.com/repository/maven-snapshots/``. A nightly workflow
(``.github/workflows/nightly.yaml``) builds and runs a ``test-latest-deps`` job.
The effect is that upstream regressions surface in Splunk's CI before upstream
even releases, at the cost of a permanently moving foundation. They publish to
Maven Central with GPG signing, and emit both a ``-all`` classified jar and an
unclassified copy because Sonatype requires a main artifact.

Grafana (``grafana/grafana-opentelemetry-java``)
------------------------------------------------

The minimal end of the spectrum, and the cleanest model for a first Dash0
distro.

**Delta vs upstream.** Three default properties (micrometer base time unit,
log4j and logback experimental log attributes), a metric filter that drops
metrics not used by Grafana Cloud dashboards, a resource provider emitting
``telemetry.distro.name`` / ``telemetry.distro.version``, a resource attribute
truncator, a ``Server-Timing`` header activator, and a version logger.

**How.** ``settings.gradle`` includes exactly four projects: ``agent``,
``custom``, ``smoke-tests``, ``testing:agent-for-testing``. There is no
``bootstrap`` module and no vendor instrumentation. The ``bootstrapLibs``
configuration exists but has no dependencies. They keep upstream's
``io.opentelemetry.javaagent.OpenTelemetryAgent`` as ``Premain-Class`` and even
set the manifest ``Implementation-Version`` to the upstream agent version
rather than their own.

The entire behavioural delta funnels through one class,
``custom/src/main/java/com/grafana/extensions/GrafanaAutoConfigCustomizerProvider.java``,
which chains ``addPropertiesSupplier``, ``addPropertiesCustomizer``,
``addMeterProviderCustomizer``, ``addMetricExporterCustomizer`` and
``addResourceCustomizer``.

Their README is unusually candid: it calls the distro "just a convenience
wrapper", documents how to migrate *off* it to upstream, and lists "fast
bugfixes" as the main reason to prefer it.

AWS (``aws-observability/aws-otel-java-instrumentation``)
---------------------------------------------------------

**Delta vs upstream.** X-Ray propagation and ID generation, AWS resource
detectors, Application Signals, SigV4-authenticated OTLP export, dynamic
(line-level) instrumentation, service events, and a Lambda layer.

**How.** They deviate from the template's naming (``otelagent`` and
``awsagentprovider`` rather than ``agent`` and ``custom``) and hoist the
relocation block to the root ``build.gradle.kts`` so it applies to every
``ShadowJar``. They ship two "bootstrap bridge" jars whose classes are embedded
at the **root** of the agent jar rather than under ``inst/``, because
``AwsAgentBootstrap`` appends the agent jar to the bootstrap classloader search
at premain so those classes are reachable from both ByteBuddy advice and the
agent classloader.

``otelagent/build.gradle.kts`` carries a long comment documenting a genuinely
nasty failure mode worth recording: bundling ``org.ow2.asm:asm:9.7`` into
``inst/org/objectweb/asm`` collided with the ASM the agent already ships there.
On Java 25 the older ASM threw ``Unsupported class file major version 69`` for
every class ByteBuddy tried to match, **silently disabling all instrumentation**
with no spans and no metrics. The fix was to exclude ``org.ow2.asm`` entirely so
their code resolves against the agent's newer copy. Anything a distro bundles
under ``inst/`` can collide with upstream's own dependencies, and the failure is
silent.

They use the Gradle ``jib`` plugin to publish a container image whose
``JAVA_TOOL_OPTIONS`` points at the bundled agent, which is structurally the same
delivery model as the Dash0 operator's instrumentation image.

Azure Application Insights (``microsoft/ApplicationInsights-Java``)
--------------------------------------------------------------------

**Delta vs upstream.** The largest functional delta of any distro here. A GC
monitor, a JFR-based profiler with an alerting subsystem, Azure Functions
instrumentation, a ``methods`` instrumentation, ETW logging on Windows, and a
compatibility bridge for the pre-OpenTelemetry "classic" Application Insights
SDK (``classic-sdk:core``, ``classic-sdk:web``).

**How.** Same template configurations, but with the two-pass
``shadowJarWithDuplicates`` then ``shadowJar`` trick described above, plus
targeted exclusion of upstream subtrees (Prometheus, Zipkin, slf4j-simple) to
control jar size and remove exporters they do not use. They also exclude
``META-INF/maven/**`` from ``inst/`` specifically to avoid false positives from
security scanners, which is a practical detail worth copying.

Pinned to released upstream ``2.31.1``.

Honeycomb (``honeycombio/honeycomb-opentelemetry-java``) - archived
--------------------------------------------------------------------

Included deliberately as the negative datapoint. The repository is archived and
its last release was built against agent **v1.33.1**, several years behind
current. The stated reason for sunsetting is that the project "has outlived its
useful lifespan" because the upstream SDK "is stable and has grown to offer most
of the ergonomic benefits that this distribution has historically offered". They
now recommend upstream plus contrib components.

The lesson is that a thin distro whose only value is configuration convenience
has a real risk of becoming a liability, because it must be re-released for
every upstream release whether or not anything of ours changed.

Sentry (``getsentry/sentry-java``)
-----------------------------------

Included as a copy-paste datapoint. ``sentry-opentelemetry-agent`` reproduces
the template's ``relocatePackages`` function and the three configurations
essentially verbatim, only reformatted. It confirms that the boilerplate is
copied rather than abstracted across the ecosystem, and that the cost of the
rail-roading is paid independently by every vendor.

Comparison
==========

.. list-table::
   :header-rows: 1
   :widths: 14 12 12 10 14 20

   * - Distro
     - Modules
     - Own instr.
     - Own entry point
     - Patches upstream
     - Upstream tracking
   * - Grafana
     - 4
     - No
     - No
     - No
     - Pinned release
   * - Sentry
     - agent + customization
     - No
     - No
     - No
     - Pinned release
   * - Elastic
     - ~20
     - Yes (OpenAI)
     - Yes
     - Yes (ASM field injection)
     - Pinned release, daily ``updatecli`` cron
   * - Splunk
     - ~25
     - Yes (13 modules)
     - Yes
     - No
     - Upstream **snapshots**, nightly build
   * - AWS
     - ~15
     - Yes
     - Yes
     - Yes (source patch queue, Lambda only)
     - Pinned release
   * - Azure
     - ~20
     - Yes
     - Yes
     - Yes (class exclusion + dedup pass)
     - Pinned release
   * - Honeycomb
     - 4
     - No
     - No
     - No
     - Abandoned at v1.33.1

Common to every distro examined
-------------------------------

* The ``bootstrapLibs`` / ``javaagentLibs`` / ``upstreamAgent`` configuration
  triple and the relocate, isolate, merge task sequence.
* ``AutoConfigurationCustomizerProvider`` registered via ``@AutoService`` as the
  primary place defaults are set.
* A ``ResourceProvider`` emitting ``telemetry.distro.name`` and
  ``telemetry.distro.version``. Grafana and Elastic both do this; it is the
  standard way a distro identifies itself in the telemetry it produces.
* A ``smoke-tests`` module that runs the assembled agent jar against a real
  application in a container and asserts on exported telemetry. Nobody trusts
  unit tests alone for this, because the failure modes are packaging failures.
* A ``testing:agent-for-testing`` module that repackages
  ``opentelemetry-agent-for-testing`` through the identical pipeline, so tests
  exercise the real packaging.
* License report generation embedded into the jar, since the artifact
  redistributes a large dependency tree.

What this means for a Dash0 Java distro
=======================================

Mapping from the Python distro
------------------------------

The Python distro's headline justification does not carry over. Its central
feature is pure-Python protobuf and OTLP exporters that eliminate
``google.protobuf`` and ``grpcio``, so the distro can be injected into an
arbitrary process without ABI or dependency conflicts. **The Java agent already
solves that problem.** Everything it loads is relocated into shaded packages
under ``inst/`` and loaded by a dedicated classloader, isolated from the
application. There is no Java equivalent of the pyproto work to do.

The remaining Python features map to Java as follows:

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - Python distro feature
     - Java equivalent
   * - ``DASH0_OTEL_COLLECTOR_BASE_URL``
     - ``addPropertiesSupplier`` default for ``otel.exporter.otlp.endpoint``.
   * - ``DASH0_DISABLE``
     - Check in a custom ``premain`` before delegating, or default
       ``otel.javaagent.enabled=false``.
   * - ``DASH0_BOOTSTRAP_SPAN``
     - ``AgentListener`` / ``BeforeAgentListener``.
   * - ``DASH0_AUTOMATIC_SERVICE_NAME``
     - Largely already upstream. The agent derives service name from jar
       manifests, Spring application name and similar. Needs a gap analysis
       rather than a reimplementation.
   * - ``DASH0_FLUSH_ON_SIGTERM_SIGINT``
     - Mostly unnecessary. The agent registers a JVM shutdown hook that flushes.
   * - Kubernetes pod UID detection
     - Upstream and contrib resource providers already cover much of this.
   * - Defensive instrumentor loading
     - Already upstream. Muzzle gates each instrumentation, and a failing module
       is skipped rather than aborting startup.
   * - Pure-Python protobuf exporters
     - Not applicable. Solved by the agent's shading.

The honest conclusion is that a Dash0 Java distro is a **much thinner artifact**
than the Python one. Its justification is defaults, Dash0-specific resource
detection, distro identification and the ability to ship a fix ahead of an
upstream release. It is not isolation.

What this led to
----------------

The decisions taken on the strength of the findings above, with their reasoning
and consequences, are recorded in `decisions/0001-distribution-approach.rst
<decisions/0001-distribution-approach.rst>`_. In short: the repackaged agent jar
is the product, the standalone extension jar stays published alongside it, every
customisation stays in ``:custom``, the distribution stays minimal until a
requirement forces otherwise, and the upstream agent is pinned to a release
rather than tracked at a snapshot.

One question this survey could not answer is why the distribution should exist
at all. Ruling out the Python distribution's rationale, as the previous section
does, removes a reason without supplying one. That remains open, and is a
product decision rather than a technical one.
