===============================
End-to-end example and check
===============================

A Java application instrumented by this distribution, exporting over OTLP to a
real OpenTelemetry Collector, with assertions on what the collector received.

Everything else in this repository checks the distribution through the console
exporter. That proves the SDK produced something; it does not prove that
anything leaves the process. This example is the only check that OTLP export
works, that real library instrumentation fires, and that the Dash0 resource
attributes survive the trip to a collector.


Running it
==========

.. code-block:: bash

    ./gradlew :agent:shadowJar
    examples/dash0-distro-java/verify.sh

The script needs ``docker``, ``docker compose`` and ``jq``. It prints one line
per assertion and exits non-zero if any fails.


What the stack contains
=======================

``app``
   ``app/DemoApplication.java`` run with ``-javaagent:`` pointed at the jar
   from ``agent/build/libs``. The only configuration it receives is
   ``DASH0_OTEL_COLLECTOR_BASE_URL``; it never names an OTLP endpoint. Any
   telemetry arriving at all is therefore proof that the variable is honoured.

``app-packaged``
   The same application in the layout a Dash0 Linux package installs: the agent
   jar at ``/usr/lib/opentelemetry/java/opentelemetry-javaagent.jar`` with
   ``dash0-resource-attributes.properties`` beside it. This is what makes
   ``dash0.package.name`` and ``dash0.package.version`` appear, and it is the
   only place that path is exercised against a real install layout.

``collector``
   ``otel/opentelemetry-collector-contrib``, receiving OTLP/HTTP and writing
   each signal to ``output/traces.jsonl``, ``output/metrics.jsonl`` and
   ``output/logs.jsonl``. It runs as the host user, so those files belong to
   whoever ran the script rather than to root.

The application contains no OpenTelemetry code and has no dependencies beyond
the JDK, so the containers compile it with ``javac`` and need no build tool.
It serves a few HTTP requests to itself and exits, which avoids a readiness
race and gives the agent a clean flush.


What is asserted
================

.. list-table::
   :widths: 30 70
   :header-rows: 1

   * - Signal
     - Assertion
   * - Traces
     - A SERVER span arrived, so ``com.sun.net.httpserver`` was instrumented.
   * - Traces
     - A CLIENT span arrived, so ``HttpURLConnection`` was instrumented.
   * - Traces
     - The server span is named ``GET /items``, after the route.
   * - Traces
     - The resource carries ``telemetry.distro.name=dash0-java``, and not
       upstream's ``opentelemetry-java-instrumentation``.
   * - Metrics
     - ``jvm.memory.used`` and ``http.server.request.duration`` arrived.
   * - Logs
     - Application log records arrived, so the ``java.util.logging`` appender
       is wired up.
   * - Resource
     - ``app-packaged`` reports both ``dash0.package.*`` attributes, and
       ``app`` reports neither, having no properties file.

The last pair is deliberate. Without the negative case, an assertion that the
package attributes are present would also pass if the provider attached them
unconditionally.


Sending it to Dash0
===================

The overlay adds a second exporter without changing either application:

.. code-block:: bash

    export DASH0_ENDPOINT=https://ingress.<your-region>.<cloud>.dash0.com
    export DASH0_AUTH_TOKEN=auth_...
    export DASH0_DATASET=default
    export SCRIPT_UID=$(id -u) SCRIPT_GID=$(id -g)
    docker compose -f docker-compose.yml -f docker-compose.dash0.yml up

Copy the endpoint from Dash0 under Settings, Endpoints, OTLP via HTTP, and
create the token under Settings, Auth Tokens.


What this does not cover
========================

Kubernetes. The Dash0 operator injects the stock upstream agent today rather
than this distribution, which ``docs/kubernetes-injection.md`` records, so a
Kubernetes scenario would have to hand-roll an injection nobody runs. The part
a Kubernetes test would uniquely cover, the OpenTelemetry injector setting
``JAVA_TOOL_OPTIONS`` through ``LD_PRELOAD``, is covered on a host by the
package end-to-end suite in `dash0-packages
<https://github.com/dash0hq/dash0-packages>`_.
