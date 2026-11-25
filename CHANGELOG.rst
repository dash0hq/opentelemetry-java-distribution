=========
Changelog
=========

All notable changes to the Dash0 OpenTelemetry Java distribution are documented
in this file.

Entries are not written here by hand. Add a file under ``.chloggen/`` instead
(``make chlog-new``); they are compiled into a new section at release time by
`chloggen
<https://github.com/open-telemetry/opentelemetry-go-build-tools/tree/main/chloggen>`_.
See CONTRIBUTING.rst and RELEASING.rst.

Releases are published as GitHub releases, with the agent jar attached as an
asset, which is what dash0-packages fetches. See RELEASING.rst.

The marker below is where ``make chlog-update`` inserts each new section.
Removing it makes that command fail with "expected one instance of
<!-- next version -->", which is why it is a reStructuredText comment rather
than ordinary prose.

.. <!-- next version -->

0.2.0 - 2026-11-25
========================================

Breaking Changes
----------------

- ``custom``: Default otel.propagators to tracecontext,baggage,xray instead of tracecontext,baggage, so a trace survives an AWS load balancer that rewrites X-Amzn-Trace-Id

  When an incoming request carries both ``traceparent`` and ``X-Amzn-Trace-Id``,
  the X-Ray context now wins. Set ``OTEL_PROPAGATORS=tracecontext,baggage`` to
  restore the previous behavior.
  

Enhancements
------------

- ``custom``: Move DistroResourceProvider to an order value no other resource provider in the agent jar uses, and document every provider the distribution ships

  ``DistroResourceProvider`` returned ``100`` from ``order()``. At agent 2.33.1
  so do ``SpringBootServiceNameDetector``, which inherits it, and
  ``AzureVmResourceProvider``. Three providers at one order value leaves the
  sequence they are applied in decided by their position in the merged
  ``META-INF/services`` file, which is a byproduct of the order
  ``mergeServiceFiles`` concatenates in. The keys are disjoint today, so
  nothing observable changes; what the tie costs is the guarantee, because a
  provider added at ``100`` later that does set ``telemetry.distro.name``
  would overwrite the Dash0 value with no error at all. No other provider in
  the built jar uses ``200``, and it still beats upstream's own
  ``DistroResourceProvider``, which declares no order and so gets ``0``.
  ``docs/resource-detection.md`` now lists all 23 upstream providers with their
  ``order()`` values and what each contributes, says which eleven are off by
  default, and gives the ``service.name`` a real JVM reported for each of eight
  launch shapes. ``docs/configuration.md`` gains the five
  ``otel.resource.providers.<group>.enabled`` toggles and a section on
  ``host.id``, which stays off: ``/etc/machine-id`` is baked into a container
  image, so every container started from one image would report the same value.
  Both files now warn that ``otel.java.enabled.resource.providers`` is an
  allow-list. Setting it to enable one detector switches off every provider it
  does not name, including ``DistroResourceProvider``, and the process then
  reports no distribution attributes at all with nothing in its output saying
  so.
  
- ``custom``: Default otel.traces.sampler to always_on, so an incoming trace context can no longer decide whether this process is traced

  The distribution set no sampler and inherited the OpenTelemetry default,
  ``parentbased_always_on``. ``ParentBased`` holds five sub-samplers, and the
  ``root`` one applies only to a span with no parent. A span whose parent
  arrived in a ``traceparent`` header is decided by ``remote_parent_sampled``
  or ``remote_parent_not_sampled`` instead, which default to ``AlwaysOn`` and
  ``AlwaysOff``.
  A caller that sends a ``traceparent`` with the sampled flag off therefore
  silenced every span in the service receiving the request, and in every
  service that one called. That caller is often outside the organization
  operating the service, so the clients of an API decided whether its owner
  got to trace it.
  ``always_on`` takes the incoming trace context out of the decision. Dash0
  samples traces after ingestion with Signal Control, which evaluates a whole
  trace at once, and a span dropped by a sampler inside the process never
  reaches that evaluation.
  Nothing stops working and no setting becomes invalid, but an existing
  deployment is affected without changing its configuration. Expect more spans
  to leave the application, costing CPU in the process and bandwidth to the
  collector, and reduce the volume with Signal Control or a collector-side
  processor rather than in the SDK. Set ``OTEL_TRACES_SAMPLER`` or
  ``-Dotel.traces.sampler`` on the workload to keep the previous behaviour; the
  value is registered through ``addPropertiesSupplier``, which both of those
  beat.
  Under ``OTEL_CONFIG_FILE`` the SDK never consults the autoconfigure SPI, so
  the default arrives by another route: the customizer described in
  ``docs/configuration.md`` sets ``tracer_provider.sampler`` on the parsed
  model when the file declares no sampler.
  
- ``custom``: Honour the Dash0 settings and contribute the Dash0 attributes under OTEL_CONFIG_FILE

  Setting ``OTEL_CONFIG_FILE`` made the SDK skip every
  ``AutoConfigurationCustomizerProvider`` and every ``ResourceProvider``, which
  is where this distribution contributed everything it contributed. A Java
  process under declarative configuration therefore ran as the stock upstream
  agent, and nothing said so. The expensive case was ``DASH0_DISABLE``: an
  operator who set it to stop a host sending telemetry kept sending and kept
  being billed.
  ``Dash0DeclarativeConfigurationCustomizerProvider`` registers under
  ``DeclarativeConfigurationCustomizerProvider``, which that mode does consult,
  and writes into the parsed configuration model. ``DASH0_DISABLE=true`` sets
  the model's ``disabled`` flag, compared against the string ``true``
  case-insensitively and after trimming as before, so ``1``, ``yes`` and ``on``
  still leave the SDK enabled. ``telemetry.distro.name``,
  ``telemetry.distro.version`` and the ``dash0.package.*`` attributes a Linux
  package writes beside the agent jar go into ``resource.attributes``, which
  the SDK applies after every detector. The upstream ``host``, ``process``
  and ``service`` detectors are named in ``resource.detection/development``,
  so ``host.name``,
  ``process.pid`` and ``service.instance.id`` come back. ``host.id`` comes with
  them, which the environment path leaves off, and ``process.command_args``
  does not, which the environment path has. ``tracer_provider.sampler`` is set to
  ``always_on`` when the file declares no sampler, and
  ``propagator.composite_list`` to ``tracecontext,baggage,xray`` when the file
  declares neither a ``composite`` nor a ``composite_list`` that is not blank.
  A file with no ``propagator`` node left the SDK on its noop propagators, so
  an outgoing request carried no ``traceparent`` at all, and a blank
  ``composite_list`` leaves it with zero propagators for the same outcome.
  ``composite: []`` and ``composite_list: none`` ask for no propagation and are
  left alone.
  ``DASH0_OTEL_COLLECTOR_BASE_URL`` fills in the ``endpoint`` of an OTLP
  exporter the file declared and left without one, across the batch and simple
  processors of all three signals and both the HTTP and gRPC exporters. It adds
  no exporter and no provider the file did not declare, since the file is the
  whole configuration under ``OTEL_CONFIG_FILE``.
  A set ``OTEL_EXPORTER_OTLP_ENDPOINT`` suppresses that mapping and leaves the
  file untouched, and configures nothing itself in this mode, so an exporter
  the file left without an ``endpoint`` keeps its own specification default,
  ``http://localhost:4318`` for ``otlp_http`` and ``http://localhost:4317`` for
  ``otlp_grpc``. The customizer logs that at ``WARNING``.
  Both Dash0 settings are read from the system property first and the
  environment variable second, the precedence the autoconfigure SPI gives the
  same two names.
  An existing deployment that sets ``OTEL_CONFIG_FILE`` is affected without
  changing its configuration, and three of the changes are visible. A process
  with ``DASH0_DISABLE=true`` stops exporting. Telemetry is attributed to
  ``dash0-java`` rather than to ``opentelemetry-javaagent``. Spans arrive that
  an incoming ``traceparent`` with the sampled flag off used to suppress. Both
  delivery shapes are covered: the repackaged agent jar and the standalone
  extension jar.
  

0.1.0 - 2026-11-09
========================================

New Components
--------------

- ``custom``: Report ``telemetry.distro.name`` as ``dash0-java`` and ``telemetry.distro.version`` as the distribution version

  These replace the upstream agent's own values, which report
  ``opentelemetry-java-instrumentation``. The Dash0 resource provider declares a
  higher ``Ordered.order()`` than upstream's so that it is applied last;
  without that the values are silently discarded.
  

Enhancements
------------

- ``ci``: Fail when the relocation rules drift from upstream's

  scripts/check-relocations.sh compares buildSrc/src/main/kotlin/Relocations.kt
  against upstream's copy at the pinned agent version, ignoring comments and
  the licence header. A rule upstream adds and this repository does not copy
  produces no error, only instrumentation that never runs.
  
- ``ci``: Compare the repackaged agent against the upstream jar it was built from

  Three of the silent failures listed in CLAUDE.md are properties of what ends
  up in the jar. AgentCompositionTest asserts that nothing upstream ships is
  dropped, that the only additions are the distribution's own files, and that
  no file inside the isolated agent tree has been rewritten. The last one is
  what catches a bundled library replaced by another version, which is the
  shape of the ASM problem that silently disabled every instrumentation for
  Amazon.
  
- ``ci``: Assert the Dash0 customisations on every supported JVM

  The supported-jvms matrix disabled every exporter and checked only that the
  application completed, so it proved the agent attached without crashing and
  nothing more. It now exports to the console and asserts the distro
  attributes, then runs again with DASH0_DISABLE=true and asserts telemetry
  stops, covering both halves of what this distribution adds across every JVM
  upstream supports rather than only on the build JDK.
  
- ``ci``: Mirror docs/ to the Dash0 website

  A pull request touching docs/ runs the sync in dry-run mode, which needs no
  secrets and fails when a file under docs/ is missing from
  transformations.yaml. A real run is workflow_dispatch only, because the three
  repository secrets it needs do not exist on this repository yet.
  
- ``config``: Pin what the distribution does under OTEL_CONFIG_FILE

  DeclarativeConfigurationTest asserts the behaviour documented in
  docs/configuration.md: no Dash0 resource provider runs, DASH0_DISABLE is
  ignored, and the upstream host and process detectors are absent. Upstream is
  actively moving on declarative configuration, so the next agent bump could
  start honouring these SPIs. The test makes that a visible failure rather than
  something nobody notices.
  
- ``config``: Honour DASH0_OTEL_COLLECTOR_BASE_URL and DASH0_DISABLE

  DASH0_OTEL_COLLECTOR_BASE_URL sets the OTLP endpoint when nothing else has.
  OTEL_EXPORTER_OTLP_ENDPOINT overrides it, and so does a signal-specific name
  such as OTEL_EXPORTER_OTLP_TRACES_ENDPOINT. The Dash0 name is what a single
  /etc/opentelemetry/injector/default_env.conf puts on a whole host, and a
  host-wide default should not silently beat a setting made on one workload.
  The Python and Ruby distributions treat it the same way, through
  environ.setdefault and Environment.set_default respectively.
  
  DASH0_DISABLE=true turns the SDK into a no-op, and overrides OTEL_SDK_DISABLED
  rather than deferring to it, because a kill switch another setting can cancel
  is not one. The value is trimmed and compared case-insensitively, matching
  Python and Ruby. The Node.js distribution is case-insensitive but does not
  trim.
  
- ``deps``: Build on the upstream OpenTelemetry Java agent 2.33.1

  The agent version determines the entire bundled instrumentation set. The Dash0
  operator's instrumentation image pins 2.32.0 in
  images/instrumentation/jvm/pom.xml and downloads the stock agent from Maven,
  so the two no longer name the same version. Nothing depends on them agreeing
  while the operator does not consume this distribution.
  
- ``docs``: Record what is settled and what is still open

  The README and the overview described the repository as scaffolding with no
  feature set, which stopped being true. The rationale question stays open and
  now cites OSS-41, the ticket that tracks it, rather than OSS-35, which is the
  setup ticket.
  
- ``examples``: Add an end-to-end example that exports to a real collector

  Every other test in this repository reads the console exporter, which proves
  the SDK produced something but not that anything leaves the process. The
  example runs the agent against an OpenTelemetry Collector over OTLP and
  asserts on what the collector received: server and client spans from real
  instrumentation, JVM and HTTP metrics, log records, the telemetry.distro.*
  attributes, and the dash0.package.* attributes in the layout a Linux package
  installs.
  
- ``resource``: Report the resource attributes a Linux package writes beside the agent jar

  Dash0PackageResourceProvider reads dash0-resource-attributes.properties from
  the directory holding the agent jar and turns every key in it into a resource
  attribute. The Dash0 Linux packages use it for dash0.package.name and
  dash0.package.version, which the Node.js, Python and Ruby packages set from
  their entry-point script. Java has no entry-point script to set them in,
  because the OpenTelemetry injector contributes only -javaagent:.
  

Bug Fixes
---------

- ``changelog``: Add the marker chloggen inserts new sections at

  CHANGELOG.rst had no "<!-- next version -->" comment, so the
  "make chlog-update" step that RELEASING.rst opens with failed with
  "expected one instance of <!-- next version -->". The first release would
  have hit this.
  
- ``resource``: Stop the package resource provider from aborting the agent under the extension jar

  Loaded from the standalone extension jar, Dash0PackageResourceProvider's own
  class reports an otel: code source rather than a file. Paths.get threw
  FileSystemNotFoundException, which propagated out of createResource, aborted
  SDK autoconfiguration and stopped the agent from starting at all, so every
  signal was lost including upstream's. The provider now resolves a directory
  only from a file: URL and never throws.
  
