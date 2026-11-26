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

0.3.0 - 2026-11-26
========================================

Breaking Changes
----------------

- ``custom``: A configuration file that pins the OTLP specification default endpoint is now repointed at DASH0_OTEL_COLLECTOR_BASE_URL

  A host that sets ``DASH0_OTEL_COLLECTOR_BASE_URL`` and runs a configuration
  file whose exporter names ``http://localhost:4318/v1/traces``, one of its two
  siblings, or ``http://localhost:4317`` for gRPC, exported to that endpoint
  before and exports to the collector base URL now. The case this takes away is
  a collector running beside the JVM, reached on the default port, on a host
  where the base URL points somewhere else.
  Set ``OTEL_EXPORTER_OTLP_ENDPOINT`` on such a workload, which stops
  ``DASH0_OTEL_COLLECTOR_BASE_URL`` being applied and hands the endpoint back to
  the configuration file. The replacement is logged at ``INFO``, naming the
  endpoint that was there and the one that took its place. Every host running
  the packaged file with the base URL set logs that line, since the packaged
  file resolves every endpoint to the specification default, so the line reports
  where telemetry goes rather than marking a host that lost a collector.
  An endpoint that differs from the specification default in any way, including
  a trailing slash or a different port, is untouched.
  

Bug Fixes
---------

- ``custom``: Apply DASH0_OTEL_COLLECTOR_BASE_URL to an OTLP exporter the configuration file left at the specification default endpoint

  On a host with the Dash0 Linux packages, ``DASH0_OTEL_COLLECTOR_BASE_URL``
  had no effect on a Java process: it exported to ``http://localhost:4318``
  unless ``OTEL_EXPORTER_OTLP_ENDPOINT`` was set as well, while Node.js, Python
  and Ruby on the same host honoured the variable.
  The file ``/etc/opentelemetry/java/otel-config.yaml`` that
  ``dash0-java-autoinstrumentation`` installs writes every endpoint as
  ``${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}/v1/traces`` and its
  two siblings. The SDK substitutes environment variables while it parses the
  file, so with ``OTEL_EXPORTER_OTLP_ENDPOINT`` unset the exporter already
  carried the specification default, and the distribution applied the collector
  base URL only to an exporter that declared no endpoint at all.
  An endpoint equal to the specification default for its signal and protocol is
  now treated as an exporter that named none. An endpoint that differs from it
  in any way, including a trailing slash or a port, is kept, and a set
  ``OTEL_EXPORTER_OTLP_ENDPOINT`` still wins over
  ``DASH0_OTEL_COLLECTOR_BASE_URL``. The breaking change above covers what a
  file that pins the specification default on purpose has to do now.
  

0.2.0 - 2026-11-25
========================================

Breaking Changes
----------------

- ``custom``: Default otel.semconv-stability.opt-in to database,code,rpc,messaging, so database, code, RPC and messaging telemetry carries the newer semantic conventions

  The distribution set nothing and inherited the upstream agent's default,
  which is the superseded convention in every domain.
  On a JDBC span, ``db.statement`` becomes ``db.query.text``, ``db.system``
  becomes ``db.system.name``, ``db.name`` becomes ``db.namespace``, and
  ``db.query.summary`` is new. Five attributes go away with nothing taking
  their place: ``db.sql.table``, ``db.operation``, ``db.connection_string``,
  ``db.user`` and ``code.namespace``. ``db.system.name`` reports a new value as
  well as a new key for fifteen systems, H2 as ``h2database`` where
  ``db.system`` said ``h2`` and SQL Server as ``microsoft.sql_server`` where it
  said ``mssql``. On a manually instrumented method, ``code.function`` and
  ``code.namespace`` are replaced by one ``code.function.name`` holding the
  class and the method together, ``TestApplication.doWork`` where
  ``code.function`` held ``doWork``. A SQL span is named after a summary of the
  query, so a span named ``SELECT testdb.widgets`` is named ``SELECT widgets``.
  The JDBC connection pool metrics are the part that fails without reporting an
  error. Every instrument is renamed from ``db.client.connections.*`` to
  ``db.client.connection.*``, with ``db.client.connections.usage`` becoming
  ``db.client.connection.count`` and ``db.client.connections.max`` becoming
  ``db.client.connection.limit``. The ``pool.name`` and ``state`` attributes
  become ``db.client.connection.pool.name`` and ``db.client.connection.state``.
  ``create_time``, ``wait_time`` and ``use_time`` are recorded in seconds
  instead of milliseconds. A renamed instrument empties a dashboard, which
  somebody notices. A threshold alert written against a duration in
  milliseconds and pointed at a series now reported in seconds keeps evaluating
  and stops firing, which nobody notices.
  ``/dup`` does not cover the connection pool metrics. The agent picks one
  name, one attribute set and one unit per instrument instead of recording
  both, so ``database/dup`` still renames them and still moves those three
  histograms to seconds. Dual emit covers attributes on spans, not the identity
  of an instrument.
  HTTP is untouched. ``http`` is not a domain this setting accepts, the HTTP
  semantic conventions are stable already, and the agent emits the stable
  attribute names whatever this setting says.
  Two of the four domains are experimental. ``database`` and ``code`` select
  conventions the semantic conventions working group has declared stable, while
  ``rpc`` and ``messaging`` select experimental ones that a later agent version
  may rename again.
  To keep the superseded names, set ``OTEL_SEMCONV_STABILITY_OPT_IN`` or
  ``-Dotel.semconv-stability.opt-in`` to a non-blank value naming no domain,
  such as ``none``. Clearing the variable does not work: a blank value is read
  as unset and the default applies again.
  This default is environment-only. A host configured from a declarative
  configuration file, which is every host where
  ``/etc/opentelemetry/java/otel-config.yaml`` exists, keeps the superseded
  names until the file says otherwise.
  ``docs/configuration.md`` has the rest: the RPC and messaging span names and
  span kinds that change, the metrics that start and stop being recorded, the
  four instrumentations that behave differently from the table, and the
  spelling a configuration file uses for the same choice.
  
- ``custom``: Configure the SDK from /etc/opentelemetry/java/otel-config.yaml when OTEL_CONFIG_FILE is unset

  A Dash0 Linux package installs an OpenTelemetry configuration file at
  ``/etc/opentelemetry/java/otel-config.yaml``, and nothing read it. The file
  stayed inert until a host administrator also set ``OTEL_CONFIG_FILE``.
  The order is now ``OTEL_CONFIG_FILE`` when it is set to anything that is not
  blank, then ``/etc/opentelemetry/java/otel-config.yaml`` when that file
  exists, then the environment variables, which is what a host without the file
  keeps doing. An ``OTEL_CONFIG_FILE`` that names a file which does not exist
  counts as set, so the SDK reports the error instead of this distribution
  quietly loading a different file.
  A host leaves the default file behind by removing or renaming it, which puts
  the next JVM back on environment configuration, or by setting
  ``OTEL_CONFIG_FILE`` for a single workload. No variable selects environment
  configuration while the file is there.
  ``OTEL_SDK_DISABLED=true`` keeps the default file from loading at all, read
  from the system property, the environment variable and the properties file
  ``otel.javaagent.configuration-file`` names, which is the same set of three
  the SDK resolves it from under environment configuration. ``DASH0_DISABLE``
  is now read from those three as well. An explicit ``OTEL_CONFIG_FILE`` is
  unaffected by either, since naming a file is an opt-in.
  A default file that cannot be read or parsed stops autoconfiguration, as does
  one that parses to no document at all. The agent reports that it failed to
  start and the JVM runs uninstrumented, with no fallback to environment
  configuration, which is what the Dash0 Python and Node.js distributions do
  with their own default file.
  A host that has the Dash0 Java package installed and sets neither variable
  changes behaviour without changing its configuration, because the shipped file
  now configures the SDK. Every standard ``OTEL_*`` variable other than
  ``OTEL_SDK_DISABLED`` stops configuring the SDK directly, and the shipped file
  answers for them by interpolating the ones you set, including
  ``OTEL_EXPORTER_OTLP_ENDPOINT`` and ``OTEL_RESOURCE_ATTRIBUTES``. The
  repackaged agent jar and the standalone extension jar both do this.
  ``docs/configuration.md`` describes the SPI the provider registers under and
  what the Dash0 customizer still contributes in this mode.
  
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
  
- ``docs``: Document how to capture MDC attributes on exported log records, with a deny list of credential-shaped keys to start from

  MDC is where an application puts the identifiers people filter logs by:
  request id, tenant, customer, user, job id. Neither the upstream agent nor
  this distribution captures them, so those values reach Dash0 only as a
  substring of the message body, if the log pattern includes them at all.
  Capture stays off until the operator turns it on, and
  ``docs/configuration.md`` now says how. Each of the three appender
  instrumentations that read an MDC selector has its own property, and the
  JBoss Log Manager one carries no ``_APPENDER_`` where the other two do:
  ``OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED``,
  ``OTEL_INSTRUMENTATION_LOG4J_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED``
  and
  ``OTEL_INSTRUMENTATION_JBOSS_LOGMANAGER_EXPERIMENTAL_MDC_ATTRIBUTES_EXCLUDED``.
  An excluded selector with no included selector beside it captures every key
  that does not match it, so one value both turns capture on and keeps the
  denied keys out.
  The deny list the documentation gives is twelve case-sensitive glob
  patterns, four stems in three spellings: ``*token*``, ``*Token*``,
  ``*TOKEN*``, ``*secret*``, ``*Secret*``, ``*SECRET*``, ``*password*``,
  ``*Password*``, ``*PASSWORD*``, ``*authorization*``, ``*Authorization*`` and
  ``*AUTHORIZATION*``. Matching is case-sensitive upstream, so the spellings
  are not redundant: ``*token*`` alone matches ``session-token`` and misses
  both ``authToken`` and ``AUTH_TOKEN``. It is a starting point rather than a
  guarantee, since it matches four stems and three spellings of each, and
  nothing inspects values.
  Two costs are documented beside it. A large MDC can displace other
  attributes, because ``OTEL_ATTRIBUTE_COUNT_LIMIT`` caps a record at 128 and
  drops the excess with no warning; on Log4j 2.17 the thread context is
  recorded before the exception, so a thread context at or over the limit
  takes ``exception.type``, ``exception.message`` and ``exception.stacktrace``
  off an error record. And these property names are marked experimental
  upstream, which may rename or remove them in any minor release.
  Under a configuration file the agent reads the selectors from the file alone
  and never from a system property or an environment variable, so a host with
  the Dash0 Java package installed opts in through an
  ``instrumentation/development`` node instead. ``docs/configuration.md`` gives
  the YAML, whose node names are not a mechanical translation of the property
  names.
  ``MdcAttributeCaptureTest`` pins all of this against a real JVM with the
  built agent attached at 2.33.1: the three property names, the globs, the
  included selector, the deprecated ``capture-mdc-attributes``, and that a
  workload setting no selector exports no MDC entry at all.
  

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
  
