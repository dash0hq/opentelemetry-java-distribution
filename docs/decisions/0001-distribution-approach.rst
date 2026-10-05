==========================================
1. Distribution approach
==========================================

:Status: Accepted
:Date: 2026-10-04
:Tracking: `OSS-41 <https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro>`_

Context
=======

This repository was scaffolded with two delivery shapes built from one source
module, deliberately leaving the choice between them open. OSS-41 asked what
other vendors do, how they do it, and how their builds work, so that the choice
could be made from evidence rather than taste.

Seven vendor distributions were read at source level: Elastic
(``elastic/elastic-otel-java``), Splunk (``signalfx/splunk-otel-java``), Grafana
(``grafana/grafana-opentelemetry-java``), Amazon
(``aws-observability/aws-otel-java-instrumentation``), Microsoft
(``microsoft/ApplicationInsights-Java``), Honeycomb
(``honeycombio/honeycomb-opentelemetry-java``) and Sentry
(``getsentry/sentry-java``), alongside upstream's ``examples/distro`` template.

The full findings are in `../vendor-distribution-survey.rst
<../vendor-distribution-survey.rst>`_. Three of them drive the decisions below.

**Upstream prescribes exactly one way to build a repackaged agent.** The
template in ``examples/distro`` fixes three Gradle configurations
(``bootstrapLibs``, ``javaagentLibs``, ``upstreamAgent``) and a relocate,
isolate, merge task sequence. Every distribution examined copies it, in several
cases including the comments. The supported extension points are additive only:
there is no supported way to replace an upstream class. Vendors needing more
left the supported path in five distinct ways, from planting a class in an
upstream package to reach package-private members, through rewriting upstream
bytecode with ASM at packaging time, to cloning upstream at a tag and applying a
patch file.

**The extension mechanism needs none of that machinery.** The agent remaps
extension classes at load time. ``ExtensionClassLoader.addFileUrl`` wraps an
extension jar in a ``RemappingUrlStreamHandler`` when the agent is running
shaded, so an extension is written against ordinary ``io.opentelemetry.*`` names
and needs no relocation at all. Upstream's own ``examples/extension`` applies no
``relocate`` calls. The agent also loads extensions embedded inside its own jar
under an ``extensions/`` prefix.

**The Python distribution's central justification does not carry over.** That
distribution exists largely to remove native ``google.protobuf`` and ``grpcio``
dependencies so it is safe to inject into an arbitrary process. The Java agent
already solves this: everything it loads is relocated under ``inst/`` and loaded
by its own class loader, and a class's identity in Java is the pair of its name
and the loader that loaded it, so the agent's copy of a library and the
application's copy are unrelated types that cannot collide. There is no Java
equivalent of the pyproto work to do.

Decision
========

Ship the repackaged agent jar as the product
--------------------------------------------

``dash0-opentelemetry-javaagent.jar`` is the artifact Dash0 ships and supports.

The Dash0 operator's integration point is a single file on a mounted volume,
referenced from ``JAVA_TOOL_OPTIONS``. Its instrumentation image places the
agent at ``/dash0-instrumentation/agents/jvm/``. A repackaged jar drops into
that unchanged.

The extension shape would require the operator to place a second file and set
``OTEL_JAVAAGENT_EXTENSIONS``. The operator already carries test cases for
reconciling ``JAVA_TOOL_OPTIONS`` when a workload has a third-party javaagent of
its own, so adding a second environment variable to reconcile is real work for
no gain. It is also the shape every vendor examined ships: Grafana, Splunk,
Elastic, Amazon and Microsoft all ship a repackaged jar.

Keep publishing the extension jar
---------------------------------

``dash0-opentelemetry-agentextension.jar`` remains a supported secondary
artifact for users who run the stock upstream agent and do not want to replace
it.

It costs almost nothing. Both artifacts are built from the same ``:custom``
module, and ``agentextension/build.gradle.kts`` is about twenty lines because
the extension shape needs no relocation. Elastic ships exactly this pair for the
same reason, describing their ``agentextension`` module as bundling the
extensions "to be used with the vanilla agent via the
``otel.javaagent.extensions`` config option".

Keep every customisation in ``:custom``
---------------------------------------

No Dash0 logic may live in ``:agent`` or ``:agentextension``. Both consume
``:custom`` unchanged. This is what makes the decision above cheap to revisit:
dropping or swapping a delivery shape moves no code.

Stay minimal until a requirement forces otherwise
--------------------------------------------------

No contents in ``:bootstrap``, no vendor instrumentation modules, and no Dash0
``Premain-Class``, until something concretely requires them.

Each is a small change when needed. Every vendor's custom entry point turns out
to be a thin delegator: Splunk's ``SplunkAgent`` calls
``OpenTelemetryAgent.agentmain`` and nothing else, and Elastic's sets one
logging property first. The manifest therefore keeps upstream's
``io.opentelemetry.javaagent.OpenTelemetryAgent``.

The cost of the alternative is visible in the sample. Elastic's distribution has
roughly twenty modules including JNI C++ compiled in Docker; Splunk's has
thirteen vendor instrumentation modules and a JFR profiler. Those exist to carry
features, and features are what should pull the structure, not the reverse.

Pin a released upstream version
--------------------------------

``gradle/libs.versions.toml`` pins a released upstream agent, bumped by
Renovate, which is configured to group the upstream agent separately and never
automerge it.

Splunk instead builds against upstream snapshots from the Sonatype snapshot
repository, which surfaces upstream regressions early at the price of a
permanently moving base. That trade is wrong for a team without operational
experience of a Java distribution. The nightly workflow already builds against
the latest upstream release, which gives most of the early warning without
making the pinned build non-reproducible.

Keep the smoke tests
--------------------

Assertions on the assembled artifacts and on a real instrumented JVM are not
optional, because every realistic failure here is silent.

A missed relocation, a class colliding under ``inst/``, a service file that was
not merged, or a resource provider ordered before upstream's, all produce a jar
that builds cleanly and then does nothing. Amazon documents a case in
``otelagent/build.gradle.kts`` where a bundled ASM collided with the agent's own
copy under ``inst/org/objectweb/asm``, and on Java 25 every class match threw
``Unsupported class file major version 69``, silently disabling all
instrumentation with no error surfaced anywhere.

Consequences
============

- A release is required for every upstream release, whether or not Dash0 code
  changed. This must stay cheap or the distribution goes stale. Honeycomb's
  distribution was archived at upstream agent v1.33.1, with the maintainers
  stating it had "outlived its useful lifespan".
- The upstream pin here and the pin in the Dash0 operator's
  ``images/instrumentation/jvm/pom.xml`` describe the same agent and must move
  together. Both are at 2.31.1 today. See ``RELEASING.rst``.
- The relocation block copied from upstream into
  ``buildSrc/src/main/kotlin/Relocations.kt`` must be re-checked against
  upstream on every major bump. It is the most likely source of a
  hard-to-diagnose break.
- Every default this distribution changes is a difference a user has to discover
  when debugging against upstream documentation.

Still open
==========

**Why this distribution exists** is not settled by this record, and is not a
technical question. With the Python distribution's rationale ruled out above,
the remaining candidates are Dash0 configuration parity with the Node.js and
Python distributions, distribution branding, and an independent support and
upgrade policy.

That is the same value proposition Honeycomb had when they archived theirs, so
it deserves an explicit answer rather than an assumed one. It is a product
decision for the owner of OSS-35 rather than something to infer from the code.

**Where the artifacts are published** also remains open, and depends partly on
the answer above. See ``RELEASING.rst``.
