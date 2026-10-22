==========================================================
Plan: ship this distribution as a Dash0 system package
==========================================================

This document is the brief for the work that takes this repository from "builds
locally, publishes nothing" to "installed by ``dash0-java-autoinstrumentation``
on a Debian or RPM host, and injected into JVM workloads the same way the Node.js,
Python and Ruby packages already are".

It describes what the `Dash0 OpenTelemetry Python distro <https://github.com/dash0hq/opentelemetry-python-distribution>`_
and `dash0-packages <https://github.com/dash0hq/dash0-packages>`_ already do, what
this repository has today, and the difference between the two. Every claim about
existing code was checked against the working tree at commit ``f8c8541`` of this
repository and the ``main`` branch of dash0-packages on 2026-10-23.


Definitions
===========

- .. _upstream agent:

  **upstream agent**: the OpenTelemetry Java agent built by
  https://github.com/open-telemetry/opentelemetry-java-instrumentation and
  attached to a JVM with ``-javaagent:<jar>``.

  Its version is pinned in ``gradle/libs.versions.toml`` and is independent of
  the version of this distribution.

- .. _distribution:

  **distribution**: a vendor-maintained layer on top of the `upstream agent`_
  that changes what the agent reports or how it is configured, without forking it.

  This repository is one. The Dash0 Node.js, Python and Ruby distributions are
  the others.

- .. _repackaged agent jar:

  **repackaged agent jar**: the artifact produced by the ``:agent`` module, which
  is the `upstream agent`_ rebuilt with the Dash0 code folded into it.

  It is used exactly as the upstream jar is used, so nothing that consumes an
  agent path has to change::

      java -javaagent:dash0-opentelemetry-javaagent-0.1.0.jar -jar app.jar

  The repository README names this the artifact Dash0 ships and supports.

- .. _extension jar:

  **extension jar**: the artifact produced by the ``:agentextension`` module,
  which carries the same Dash0 code but is loaded by an *unmodified* `upstream agent`_
  through ``OTEL_JAVAAGENT_EXTENSIONS``.

  It is a supported secondary artifact. It is not the subject of this plan, which
  concerns only the `repackaged agent jar`_, but no work here may break it.

- .. _resource provider:

  **resource provider**: a class implementing
  ``io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider`` that contributes
  attributes to the resource every span, metric and log record carries.

  ``custom/src/main/java/com/dash0/opentelemetry/DistroResourceProvider.java`` is
  the only one this repository ships today.

- .. _relocation:

  **relocation**: rewriting the package name of a class at build time so that the
  copy inside a jar cannot be confused with a copy of the same library elsewhere.

  ``buildSrc/src/main/kotlin/Relocations.kt`` holds the rules, copied from
  upstream deliberately. `Resource providers`_ are excluded from relocation
  because they are loaded by the agent class loader, and relocating one means it
  is never invoked.

- .. _smoke test:

  **smoke test**: in this repository, a test that asserts on the built jars or on
  the telemetry a genuinely instrumented JVM exports, rather than on classes in
  isolation.

  They live in ``smoke-tests/``. ``CLAUDE.md`` lists five build mistakes that
  produce no error of any kind, and these tests are the only thing that catches
  them. A failing `smoke test`_ is a real defect and never a flaky test.

- .. _injector:

  **injector**: the shared library from
  https://github.com/open-telemetry/opentelemetry-injector that a host loads
  through ``LD_PRELOAD``, and which appends ``-javaagent:<path>`` to
  ``JAVA_TOOL_OPTIONS`` as each process starts.

  It acts at process start rather than at pod-spec time because a container's own
  entrypoint may set those variables, so their final values are only knowable once
  the process is running.

- .. _drop-in:

  **drop-in**: a file in ``/etc/opentelemetry/injector/conf.d/`` that tells the
  `injector`_ where one language's agent is installed.

  The files are read in alphabetical order, one per language package. Java's key
  takes a single jar path, which is the detail that makes this work smaller than
  the Python equivalent::

      jvm_auto_instrumentation_agent_path=/usr/lib/opentelemetry/java/dash0-opentelemetry-javaagent.jar

  Python and Ruby instead declare a *prefix*, to which the `injector`_ appends a
  libc flavour such as ``glibc``.

- .. _conffile:

  **conffile**: a file a package marks as configuration, so that a package upgrade
  does not silently revert an edit an operator made to it.

  Every `drop-in`_ ships as one. ``builder.ConfigFile`` is what marks it.

- .. _component:

  **component**: in dash0-packages, one buildable package, declared as a
  ``builder.Component`` value and listed in ``All`` in
  ``internal/components/components.go``.

  There are four today: ``Injector``, ``Nodejs``, ``Python`` and ``Ruby``. This
  plan adds a fifth.

- .. _displace:

  **displace**: to declare the ``Provides``, ``Conflicts`` and ``Replaces``
  relations that let a Dash0 package take over the files of the equivalent
  upstream package on an upgrade.

  ``replaces()`` in ``internal/components/components.go`` builds the triple from a
  language name, producing ``opentelemetry-<language>-autoinstrumentation``.


Notation
========

- .. _V:

  ``V`` is the version of this distribution that a release publishes, the value
  that ``version`` in ``gradle.properties`` carries with ``-SNAPSHOT`` removed.

- .. _R:

  ``R`` is the packaging revision, a counter owned by dash0-packages and stored in
  ``packaging/common/<language>/revision.txt``.

- .. _D0:

  ``D0`` is the set of five environment variables the Dash0 Node.js and Python
  distributions expose: ``DASH0_DISABLE``, ``DASH0_OTEL_COLLECTOR_BASE_URL``,
  ``DASH0_AUTOMATIC_SERVICE_NAME``, ``DASH0_BOOTSTRAP_SPAN`` and
  ``DASH0_FLUSH_ON_SIGTERM_SIGINT``.

- .. _JU:

  ``JU`` is the stock `upstream agent`_ jar, which the Dash0 operator bakes into
  its instrumentation image today.

- .. _JO:

  ``JO`` is the jar the Dash0 operator's init container copies to
  ``/__otel_auto_instrumentation/agents/jvm/opentelemetry-javaagent.jar`` on a
  shared volume.

- .. _JP:

  ``JP`` is the jar the new Dash0 Java package installs at
  ``/usr/lib/opentelemetry/java/dash0-opentelemetry-javaagent.jar`` on a Debian or
  RPM host.


Where this repository stands today
==================================

This repository is already a working `distribution`_, just an almost empty one.
The build works and is tested. ``./gradlew build`` compiles, runs the unit tests,
assembles both jars and runs the `smoke tests`_, which launch a real JVM with the
agent attached.

One customisation is implemented end to end:
``DistroResourceProvider`` sets ``telemetry.distro.name`` to ``dash0-java`` and
``telemetry.distro.version`` to `V <#v>`__, read at runtime from
``dash0-distro-version.properties`` which the Gradle ``generateVersionResource``
task writes into the jar.

Three facts bound everything below.

**Nothing is published.** ``version`` in ``gradle.properties`` is
``0.1.0-SNAPSHOT``, the repository has no releases, and no Gradle publishing is
configured. The ``mavenCentral()`` entries in ``settings.gradle.kts`` and
``buildSrc/settings.gradle.kts`` declare where dependencies are *consumed from*,
not where artifacts are published to. ``RELEASING.rst`` documents the intended
process and states in its opening note that the publishing destination is an open
decision.

**There is no Dash0 Java package.** dash0-packages has no `component`_ for Java,
no ``packaging/common/java/`` directory and no ``tests/e2e/java/``. Searching its
Go sources for "java" matches one unrelated line in
``internal/components/nodejs.go``.

**The configuration surface is empty.** ``docs/configuration.md`` states that this
distribution exposes none of `D0 <#d0>`__ and sets no defaults of its own.


What the Python work consisted of, and what transfers
=====================================================

The Python arc is the template the question refers to. It had two halves that are
worth separating, because only one of them transfers cleanly.

The half that transfers is the packaging. A `component`_ in dash0-packages
declares a package, a ``ContentsFunc`` assembles a staging directory, and the
framework turns that into a ``.deb`` and an ``.rpm``. ``internal/components/ruby.go``
is the smallest worked example and ``internal/components/python.go`` the most
involved.

The half that does **not** transfer is the reason the Python distribution exists.
It exists largely to keep native ``grpcio`` and ``protobuf`` out of the tree that
gets injected into an arbitrary Python process. Java has no equivalent problem: the
`upstream agent`_ is already a single shaded jar loaded by its own class loader, and
a class's identity in Java is its name together with its loader, so the agent's copy
of a library and the application's copy are unrelated types that cannot collide.
The README of this repository says so directly, and says the rationale for a Java
distribution is therefore still open.

**Do not try to settle that question as part of this work.** It is recorded here so
the implementing session does not rediscover it mid-task and stall. The packaging
work below is worth doing on its own: it gives Java the same installation and
injection story the other three languages have, whatever the distribution
eventually justifies itself on.

Two further differences make the Java packaging smaller than Python's:

.. list-table::
   :widths: 30 35 35
   :header-rows: 1

   * - Concern
     - Python package
     - Java package
   * - What is installed
     - a resolved tree of wheels
     - one jar, `JP <#jp>`__
   * - Architecture
     - per-architecture, because the closure contains compiled ``psutil``
     - ``Noarch``, since a jar is bytecode
   * - libc flavour
     - the `drop-in`_ names a prefix and the `injector`_ appends ``glibc``
     - the `drop-in`_ names the jar path directly
   * - Dependency manifest
     - ``all-dependencies.txt``, so startup checks can stand the distro down
     - none needed, since class loader isolation removes the conflict


The blocker: there is nothing to fetch
======================================

Every existing `component`_ fetches a published artifact. ``Python`` fetches
``dash0-opentelemetry`` from PyPI at the version pinned in
``packaging/common/python/release.txt``. ``Nodejs`` fetches from npm. ``Ruby``
fetches a pinned git ref.

``internal/fetch/`` offers ``PyPI``, ``NPM``, ``RubyGems``, ``Git`` and ``Zig``,
alongside ``ReadPinnedVersion``, ``VerifySHA256`` and ``VerifySRI``. **None of them
fetches a GitHub release asset.**

The decision taken for this work is that the `repackaged agent jar`_ is published
as a GitHub release asset of this repository, and that
``packaging/common/java/release.txt`` pins the tag. That choice keeps the artifact
under Dash0's own control, needs no Sonatype account or signing key, and matches
how the operator already consumes pinned artifacts.


Work item A: publish a release from this repository
===================================================

Goal: pushing a tag ``v<V>`` produces a GitHub release whose assets include
``dash0-opentelemetry-javaagent-<V>.jar`` and a checksum for it.

1. Add a release workflow at ``.github/workflows/release.yml``. The repository has
   only ``ci.yml`` and ``nightly.yml`` today. Model the structure on
   ``.github/workflows/release.yml`` in the Python distribution, which is
   deliberately draft-first: assets upload to a draft release and the release is
   published as the final step, so a release is never visible with an incomplete
   asset set. Carry over its security posture, specifically per-job
   least-privilege ``permissions``, third-party actions pinned by commit SHA, and
   event fields reaching the shell only through environment indirection.
2. Publish a SHA-256 checksum beside the jar. The `component`_ in work item B
   verifies it with ``fetch.VerifySHA256``, and without a published checksum that
   verification cannot exist.
3. Decide whether the `extension jar`_ ships as a release asset too. It costs one
   line and keeps the secondary delivery shape real rather than theoretical.
4. Update ``RELEASING.rst``. Its opening note currently says the publishing
   destination is undecided, and that note has to go once this lands. The
   versioning section and the ``Cutting a release`` steps stay as they are; they
   already describe tagging ``v<version>``.
5. Cut ``v0.1.0`` so that work item B has something real to fetch. Do not leave
   dash0-packages pointing at a tag that does not exist.

Watch for: the jar manifest convention ``Implementation-Version: dash0-<V>-otel-<upstream version>``
is already documented in ``RELEASING.rst``. Confirm the built jar actually carries
it before cutting the first release, because it is the only thing that records
which `upstream agent`_ a given `JP <#jp>`__ was built from.


Work item B: the dash0-packages Java component
==============================================

Goal: ``dash0-java-autoinstrumentation`` builds as a ``.deb`` and an ``.rpm``,
installs `JP <#jp>`__, and registers it with the `injector`_.

This work happens in https://github.com/dash0hq/dash0-packages, not in this
repository.

1. Create ``packaging/common/java/`` with the five files the other languages have.
   Upstream's own Java package, vendored at
   ``packaging/common/java/`` inside
   ``github.com/open-telemetry/opentelemetry-packaging``, is the reference for four
   of them:

   .. list-table::
      :widths: 34 66
      :header-rows: 1

      * - File
        - Contents
      * - ``injector.conf``
        - the `drop-in`_, setting ``jvm_auto_instrumentation_agent_path`` to the
          path of `JP <#jp>`__
      * - ``release.txt``
        - the pinned tag of this repository, with the ``# renovate:`` comment line
          the other languages carry
      * - ``revision.txt``
        - `R <#r>`__, starting at ``1``
      * - ``opentelemetry-java.8.tmpl``
        - the man page template, named to match the upstream package whose man page
          path this package takes over
      * - ``README.md``
        - installed to ``/usr/share/doc/dash0-java-autoinstrumentation/README.md``

2. Add ``internal/fetch/github.go`` with a function that downloads a named asset
   from a release of a given repository and verifies its checksum. ``fetch.go``
   already provides ``DefaultClient`` and ``DefaultTimeout``, and ``verify.go``
   provides ``VerifySHA256``, so this is a small addition rather than a new
   subsystem. Give it a test alongside the existing ``*_test.go`` files.
3. Add ``internal/components/java.go``, declaring::

       var Java = builder.Component{
           Name:         javaName,
           PackageName:  javaPackageName,
           Description:  "Dash0 OpenTelemetry Java Auto-Instrumentation",
           Relations:    replaces(javaName),
           ContentsFunc: javaContents,
       }

   ``replaces("java")`` produces ``opentelemetry-java-autoinstrumentation``, which
   is the name upstream's own vendor API test uses, so the helper needs no change.
   Mark the `component`_ ``Noarch``: a jar is architecture-independent, which is
   the one place the Java package genuinely differs from the Python one.
4. Add ``Java`` to ``All`` in ``internal/components/components.go`` and add
   ``javaInstallDir = installDir + "/java"`` to the path constants beside
   ``nodejsInstallDir``, ``pythonInstallDir`` and ``rubyInstallDir``.
5. Have ``javaContents`` fetch the asset, verify it, generate the man page with
   ``builder.GenerateManPage``, and return ``files.Contents`` holding the jar, the
   `drop-in`_ as a `conffile`_, the man page and the README.

   Fail loudly if the fetched asset is missing or its checksum does not match.
   ``pythonContents`` does exactly this for its bootstrap file and the comment
   there explains why: shipping a package whose entry point silently does nothing
   is worse than failing the build.

Watch for: ``revision.txt`` discipline. `R <#r>`__ is bumped when a new package is
built from the same `V <#v>`__, and **reset to 1 whenever ``release.txt`` changes**.
The ``pins`` CI job in dash0-packages enforces this and will fail the build if it
is missed.


Work item C: the configuration surface
======================================

Goal: decide and implement which of `D0 <#d0>`__ this distribution honours.

``docs/configuration.md`` already contains the analysis, including a table mapping
each variable to what the `upstream agent`_ provides natively. Reproduced here so
the implementing session does not have to open the file to plan:

.. list-table::
   :widths: 45 55
   :header-rows: 1

   * - Variable in `D0 <#d0>`__
     - What Java offers today
   * - ``DASH0_DISABLE``
     - ``OTEL_JAVAAGENT_ENABLED=false`` or ``OTEL_SDK_DISABLED=true``
   * - ``DASH0_AUTOMATIC_SERVICE_NAME``
     - ``JarServiceNameDetector`` and the Spring Boot application-name detector,
       both on by default
   * - ``DASH0_FLUSH_ON_SIGTERM_SIGINT``
     - the JVM shutdown hook the agent already registers
   * - ``DASH0_OTEL_COLLECTOR_BASE_URL``
     - nothing; ``OTEL_EXPORTER_OTLP_ENDPOINT`` is the only route
   * - ``DASH0_BOOTSTRAP_SPAN``
     - nothing

Three of the five are already covered by the agent, which means implementing them
buys naming consistency and nothing functional. Two have no equivalent.

Anything this distribution sets belongs in an
``AutoConfigurationCustomizerProvider`` in ``custom/``, and ``docs/configuration.md``
has to be updated in the same change: it currently asserts that the distribution
sets no defaults of its own, and that sentence becomes false the moment one is
added.

Note the ordering dependency: ``DASH0_OTEL_COLLECTOR_BASE_URL`` is the variable the
Dash0 operator sets, so if the operator is ever to point at this distribution
without special-casing Java, that one is the most valuable of the five.


Work item D: resource detection
===============================

Goal: decide whether Java needs a Kubernetes detector, and implement the service
name behaviour if it diverges from upstream's.

``docs/resource-detection.md`` records that the Node.js and Python distributions
ship a ``k8s.pod.uid`` detector and that Java deliberately does not, because the
operator's `injector`_ already supplies ``k8s.namespace.name``, ``k8s.pod.name``,
``k8s.pod.uid`` and ``k8s.container.name`` through ``OTEL_RESOURCE_ATTRIBUTES`` for
every runtime. That reasoning holds for injected deployments and not for a JVM
someone starts by hand with `JP <#jp>`__ on a plain host, which is exactly the
case the new package creates.

Any new `resource provider`_ must return an ``order()`` above upstream's. This is
the first of the five silent failures ``CLAUDE.md`` lists: providers are applied in
ascending order and later ones win, so a provider with too low an order runs,
produces the right resource in isolation, and has its values quietly discarded.
``DistroResourceProvider.order()`` returns ``100`` for that reason. Unit tests pass
either way. Only ``DistroResourceAttributesTest`` in ``smoke-tests/`` catches it.


Work item E: tests
==================

Two test suites need a Java entry, both in dash0-packages.

``tests/validate/validate_test.go`` holds a ``specs`` slice with one entry per
`component`_, declaring the package name, the package it `displaces`_,
its generation, whether it is ``noarch``, its `drop-in`_ path, its man page path
and a ``requiredFiles`` list of paths the package is useless without. Add a Java
entry. ``requiredFiles`` should name the path of `JP <#jp>`__ at minimum.

``tests/e2e/`` holds one package per language, each installing the built package
from a local repository into a real base image and asserting on what reaches an
OTLP sink. ``tests/e2e/ruby/ruby_test.go`` is the shortest and the best model. A
Java equivalent needs:

- a ``matrix`` of ``harness.Target`` values pairing each package format with a base
  image that uses it, following the Ruby pattern of one ``deb`` row and one ``rpm``
  row, with a JDK base image in place of the Ruby one
- a test application that imports nothing from OpenTelemetry, so that any span it
  produces proves the whole injection path worked
- assertions read from the sink, never from the application

The point of the Ruby test's own comment is worth repeating: the assertions do not
vary by row, because a package that behaves differently on ``dnf`` than on ``apt``
is precisely the bug the matrix exists to find.

Separately, this repository's `smoke tests`_ stay as they are. They test the jar,
and the jar does not change shape because a ``.deb`` now carries it. If any work
here does touch the build, re-read the `relocation`_ rules first: they are copied
from upstream and must stay in sync with it, and a mistake there disables
instrumentation with no error at all.


Work item F: documentation
==========================

``docs/kubernetes-injection.md`` opens with a note saying that nothing in this
repository is wired into the Dash0 operator. That note stays accurate after this
work, because installing a host package is not the same as changing what the
operator injects, and nothing here touches
https://github.com/dash0hq/dash0-operator.

What does become stale:

- ``RELEASING.rst``, whose opening note says the publishing destination is
  undecided. Work item A settles it.
- ``docs/configuration.md``, if work item C adds anything.
- ``docs/resource-detection.md``, if work item D adds a detector.
- ``README.rst``, whose ``Status and open decisions`` section says the repository
  is scaffolding with no feature set. Revisit the wording once there is a published
  release.

Add a changelog entry for each user-visible change. ``CHANGELOG.rst`` and the
``make chlog-update`` flow described in ``RELEASING.rst`` are already in place.


What this plan deliberately does not decide
===========================================

**Why this distribution exists.** The README states the question plainly and
answers it honestly, which is to say not at all. Honeycomb archived its Java
distribution with substantially the value proposition this one has. The question is
real and it is not a packaging question, so it is out of scope here. Flag it, do not
stall on it.

**Whether the operator switches to this distribution.** Today the operator bakes
`JU <#ju>`__ into its image and its init container copies it to
``/__otel_auto_instrumentation/agents/jvm/opentelemetry-javaagent.jar``, which is
`JO <#jo>`__. Pointing the operator at this distribution means `JO <#jo>`__ becomes
the `repackaged agent jar`_ instead of `JU <#ju>`__. That is a one-file swap on the
operator side, since the `injector`_ configuration already names a single jar path
and does not care which jar is there. It is a separate change in a separate
repository, and it should not happen before there is a published release and a
working package.

Note that `JO <#jo>`__ and `JP <#jp>`__ are different objects with different owners,
and the work below only creates `JP <#jp>`__. Keeping them distinct avoids the
mistake of assuming that packaging the agent for a host also changes what Kubernetes
workloads get.

**Which of** `D0 <#d0>`__ **to implement.** Work item C lays out the trade-off and
stops there, because three of the five are already covered by the `upstream agent`_
and the answer depends on how much the operator eventually relies on Dash0 naming.


Suggested order
===============

Work item A first and alone: nothing downstream can be built or tested until a
release exists. Then B, which is the bulk of the packaging work, with E's validate
entry written alongside it rather than afterwards, because the validate spec is
the cheapest check that the `component`_ declares what it should.

E's end-to-end test next, since it is the first thing that proves the `injector`_
actually attaches `JP <#jp>`__ to a JVM.

C and D after that, in either order, and only once someone has answered the
questions they each open. They change what the distribution does rather than how it
is delivered, and the delivery path is what this plan is for.

F throughout, not at the end.

.. _displaces: displace_
.. _resource providers: resource provider_
.. _smoke tests: smoke test_
