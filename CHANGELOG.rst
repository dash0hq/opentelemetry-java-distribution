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
  
