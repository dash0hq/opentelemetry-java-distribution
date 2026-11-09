================================
Dash0 OpenTelemetry Java Distro
================================

The Dash0 distribution of OpenTelemetry for Java: a thin, opinionated layer on
top of the upstream `OpenTelemetry Java agent
<https://github.com/open-telemetry/opentelemetry-java-instrumentation>`_.

Releases carry the repackaged agent jar and the extension jar as assets, and
are published at https://github.com/dash0hq/opentelemetry-java-distribution/releases.
RELEASING.rst describes how one is cut and CHANGELOG.rst what each contains.


Status
======

Settled
-------

Delivery shape
   The repackaged agent jar is the product. ``:agent`` produces
   ``dash0-opentelemetry-javaagent.jar``, used as ``-javaagent:<jar>``, which
   drops straight into the Dash0 operator, whose integration point is a single
   file on a mounted volume referenced from ``JAVA_TOOL_OPTIONS``. It is also
   the shape Grafana, Splunk, Elastic, Amazon and Microsoft all ship.

   ``:agentextension`` produces ``dash0-opentelemetry-agentextension.jar``, the
   same Dash0 code as a standalone extension loaded by the *unmodified*
   upstream agent via ``OTEL_JAVAAGENT_EXTENSIONS``. It is a supported
   secondary artifact, published alongside the agent jar, for users who would
   rather keep the stock agent. Elastic ships this same pair.

   Keeping both costs almost nothing: every line of Dash0 logic lives in
   ``:custom`` and is consumed unchanged by both, and the extension shape needs
   no package relocation, so ``agentextension/build.gradle.kts`` is about
   twenty lines.

Dash0 environment variables
   ``DASH0_OTEL_COLLECTOR_BASE_URL`` and ``DASH0_DISABLE`` are implemented.
   ``DASH0_AUTOMATIC_SERVICE_NAME``, ``DASH0_BOOTSTRAP_SPAN`` and
   ``DASH0_FLUSH_ON_SIGTERM_SIGINT`` are not, because the upstream agent
   already covers two of them and the third has no Java counterpart. See
   ``docs/configuration.md``.

Linux package attributes
   A package installing the agent jar writes
   ``dash0-resource-attributes.properties`` beside it, and every key in that
   file becomes a resource attribute. This replaces the entry-point script the
   Node.js, Python and Ruby packages use, which Java has no equivalent of. See
   ``docs/resource-detection.md``.

Declarative configuration
   Not supported. Under ``OTEL_CONFIG_FILE`` neither the Dash0 resource
   providers nor the Dash0 environment variables take effect, which is measured
   rather than assumed in ``docs/configuration.md``. Supporting it needs a
   different pair of SPIs and is planned as a separate release after v0.1.0.

Kubernetes detection
   No ``k8s.pod.uid`` detector, unlike Node.js and Python. The reasoning, and
   the case this leaves uncovered, are in ``docs/resource-detection.md``.

Supported JVMs
   Whatever the upstream agent supports, currently Java 8 and later, and
   nothing beyond that. See ``docs/getting-started.md``.

Releasing
   Pushing a ``vX.Y.Z`` tag builds both jars and publishes them as GitHub
   release assets with a ``SHA256SUMS`` file. See RELEASING.rst.

Open
----

**Why does it exist?** The Dash0 Python distribution exists largely to remove
native ``google.protobuf`` and ``grpcio`` dependencies so it is safe to inject
into an arbitrary process. **That reasoning does not carry over to Java.** The
upstream Java agent is already a single shaded jar loaded by its own class
loader, and a class's identity in Java is its name *together with* the loader
that loaded it, so the agent's copy of a library and the application's copy are
unrelated types that cannot collide.

The justification has to be something else. The candidates are Dash0
configuration parity with the Node.js and Python distributions, distribution
branding, and an independent support and upgrade policy. That is the same value
proposition Honeycomb had when they archived their Java distribution, so it
deserves an explicit answer. Tracked in `OSS-41
<https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro>`_.
Until there is an answer, this README does not claim a rationale it cannot back
up.


What works today
================

Three customisations, all in ``:custom``, all covered by tests that run a real
instrumented JVM:

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Class
     - What it does
   * - ``DistroResourceProvider``
     - Reports ``telemetry.distro.name`` as ``dash0-java`` and
       ``telemetry.distro.version`` as the version of this distribution, on
       every span, metric and log record.
   * - ``Dash0PackageResourceProvider``
     - Reports the attributes a Linux package writes beside the agent jar,
       which is how ``dash0.package.name`` and ``dash0.package.version`` reach
       the resource.
   * - ``Dash0ConfigurationCustomizerProvider``
     - Applies ``DASH0_OTEL_COLLECTOR_BASE_URL`` and ``DASH0_DISABLE``.

Everything else is upstream behaviour, unchanged.

Each of the three fails silently when it is wired up wrongly, which is why the
smoke tests exist. ``DistroResourceProvider`` has to sort *after* the agent's
own provider setting the same two attributes, or its values are discarded with
no error. ``Dash0PackageResourceProvider`` finds its file through the code
source of a class the agent loads from ``inst/`` as a ``.classdata`` entry, and
a failure there just leaves the attributes absent.
``Dash0ConfigurationCustomizerProvider`` depends on an environment variable
name resolving to the property name it reads, and a mismatch means the setting
is ignored. In all three cases the unit tests still pass, so the only thing
that catches the mistake is asserting on what a real JVM exports.


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

``DASH0_OTEL_COLLECTOR_BASE_URL`` and ``DASH0_DISABLE`` are honoured, and all
standard ``OTEL_*`` configuration works because the upstream agent handles it
unchanged. See ``docs/configuration.md``.


Relationship to the other Dash0 distributions
==============================================

- Node.js: https://github.com/dash0hq/opentelemetry-js-distribution
- Python: https://github.com/dash0hq/opentelemetry-python-distribution
- Ruby: https://github.com/dash0hq/opentelemetry-ruby-distribution
- .NET: https://github.com/dash0hq/opentelemetry-dotnet-distribution

The Dash0 operator currently instruments JVM workloads with the **stock upstream
agent**, pinned in ``images/instrumentation/jvm/pom.xml`` in
`dash0-operator <https://github.com/dash0hq/dash0-operator>`_. Nothing in this
repository is wired into the operator yet.
