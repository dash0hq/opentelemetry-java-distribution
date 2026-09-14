================================
Dash0 OpenTelemetry Java Distro
================================

The Dash0 distribution of OpenTelemetry for Java: a thin, opinionated layer on
top of the upstream `OpenTelemetry Java agent
<https://github.com/open-telemetry/opentelemetry-java-instrumentation>`_.

.. warning::

   **This repository is scaffolding.** It builds, it is tested, and it does one
   real thing (see `What works today`_), but the shape of the finished product
   has not been decided yet. See `Status and open decisions`_ before building on
   it.


Status and open decisions
=========================

The analysis behind this repository is tracked in `OSS-41
<https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro>`_.
Two questions are deliberately still open, and the repository is laid out so
that neither has to be answered yet.

**1. How is it delivered?** There are two viable shapes, and this repository
builds both from the same source:

- ``:agent`` produces ``dash0-opentelemetry-javaagent.jar``, the upstream agent
  repackaged with the Dash0 code folded in. Used as ``-javaagent:<jar>``. This is
  what Grafana, Splunk, Elastic and Amazon all ship.
- ``:agentextension`` produces ``dash0-opentelemetry-agentextension.jar``, the
  same Dash0 code as a standalone extension loaded by the *unmodified* upstream
  agent via ``OTEL_JAVAAGENT_EXTENSIONS``. Elastic ships this alongside their
  repackaged agent.

Every line of Dash0 logic lives in ``:custom`` and is consumed unchanged by both.
Choosing one, or keeping both, does not require moving any code.

**2. Why does it exist?** The Dash0 Python distribution exists largely to remove
native ``google.protobuf`` and ``grpcio`` dependencies so it is safe to inject
into an arbitrary process. **That reasoning does not carry over to Java.** The
upstream Java agent is already a single shaded jar in its own class loader with
no native code and no interaction with the application classpath.

So the justification for this distribution has to be something else, and that
has not been settled. The candidates are Dash0 configuration parity with the
Node.js and Python distributions (a ``DASH0_*`` environment variable surface),
distribution branding, and an independent support and upgrade policy. Until that
is decided, this README deliberately does not claim a rationale it cannot back
up.


What works today
================

One customisation is implemented end to end, as a working example of the wiring
rather than as a feature set:

.. list-table::
   :widths: 34 66
   :header-rows: 1

   * - Attribute
     - Value
   * - ``telemetry.distro.name``
     - ``dash0-java``
   * - ``telemetry.distro.version``
     - the version of this distribution

Both are emitted on every span, metric and log record. They replace the upstream
agent's own values, which report ``opentelemetry-java-instrumentation``.

That override is less trivial than it looks, and the reason is worth knowing
before adding anything else: the agent ships its own resource provider setting
the same attributes, so the Dash0 provider has to sort *after* it. Getting that
wrong produces no error of any kind, and the only thing that catches it is
asserting on what a real instrumented JVM exports. See
``custom/src/main/java/com/dash0/opentelemetry/DistroResourceProvider.java`` and
``smoke-tests/.../DistroResourceAttributesTest.java``.


Architecture
============

::

    custom/           every Dash0-authored SDK customisation, and nothing else
    bootstrap/        classes needing the bootstrap class loader (empty for now)
    agent/            delivery shape 1: the repackaged agent jar
    agentextension/   delivery shape 2: the standalone extension jar
    smoke-tests/      checks the assembled artifacts and a real instrumented JVM
    buildSrc/         the packaging recipe, copied from upstream on purpose

The module names are deliberately identical to upstream's `examples/distro
<https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/examples/distro>`_.
Every vendor distribution uses them, which keeps upstream's build recipe
directly reusable.


Building
========

.. code-block:: bash

    ./gradlew build

That compiles, runs the unit tests, assembles both jars, and runs the smoke
tests, which include launching a real JVM with the agent attached. The artifacts
land in:

- ``agent/build/libs/dash0-opentelemetry-javaagent-<version>.jar``
- ``agentextension/build/libs/dash0-opentelemetry-agentextension-<version>.jar``

See CONTRIBUTING.rst for prerequisites and the development workflow.


Trying it
=========

Repackaged agent:

.. code-block:: bash

    java -javaagent:agent/build/libs/dash0-opentelemetry-javaagent-0.1.0-SNAPSHOT.jar \
      -Dotel.service.name=my-service \
      -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
      -jar my-application.jar

The same Dash0 code as an extension on the stock upstream agent:

.. code-block:: bash

    java -javaagent:opentelemetry-javaagent.jar \
      -Dotel.javaagent.extensions=agentextension/build/libs/dash0-opentelemetry-agentextension-0.1.0-SNAPSHOT.jar \
      -Dotel.service.name=my-service \
      -jar my-application.jar

There are no ``DASH0_*`` environment variables yet; that surface is one of the
open decisions above. All standard ``OTEL_*`` configuration works, because the
upstream agent handles it unchanged.


Relationship to the other Dash0 distributions
==============================================

- Node.js: https://github.com/dash0hq/opentelemetry-js-distribution
- Python: https://github.com/dash0hq/opentelemetry-python-distribution

The Dash0 operator currently instruments JVM workloads with the **stock upstream
agent**, pinned in ``images/instrumentation/jvm/pom.xml`` in
`dash0-operator <https://github.com/dash0hq/dash0-operator>`_. Nothing in this
repository is wired into the operator yet.
