============
Contributing
============

All commands below are run from the repository root.


Prerequisites
=============

- A JDK 21 or later. The build targets Java 8 bytecode via ``--release 8``, but
  it compiles with a modern toolchain.
- Go, only for the changelog helpers in the ``Makefile``.

Gradle itself does not need to be installed; use the wrapper (``./gradlew``).


Project layout
==============

- ``custom/`` — every Dash0-authored SDK customisation. **This is where new code
  goes.** It is consumed unchanged by both ``:agent`` and ``:agentextension``,
  which is what keeps the delivery decision in OSS-41 open.
- ``bootstrap/`` — classes that must live in the bootstrap class loader, visible
  both to the instrumented application and to the agent. Empty today, and kept
  because putting a class in the wrong class loader fails silently.
- ``agent/`` — the repackaged agent jar.
- ``agentextension/`` — the standalone extension jar.
- ``smoke-tests/`` — assertions about the assembled artifacts and about a real
  JVM running with the agent attached.
- ``buildSrc/`` — the packaging recipe. Read ``CLAUDE.md`` before changing
  anything here.


Building and testing
====================

.. code-block:: bash

    ./gradlew build          # compile, test, assemble both jars, smoke-test
    ./gradlew :custom:test   # just the unit tests
    ./gradlew :smoke-tests:test
    ./gradlew spotlessApply  # fix formatting

Formatting is google-java-format, enforced in CI. ``spotlessApply`` fixes every
violation it reports.

The build compiles with ``-Werror``. Two lint categories are switched off, both
documented with their reasons in
``buildSrc/src/main/kotlin/dash0.java-conventions.gradle.kts``. Do not add a
third without recording why.


Adding a customisation
======================

New SDK behaviour is a class in ``custom/`` annotated with ``@AutoService`` for
the relevant OpenTelemetry SPI. The ones worth knowing:

.. list-table::
   :widths: 44 56
   :header-rows: 1

   * - SPI
     - Use it for
   * - ``AutoConfigurationCustomizerProvider``
     - Defaults, endpoint configuration, translating ``DASH0_*`` variables
   * - ``ResourceProvider``
     - Resource attributes (see ``DistroResourceProvider``)
   * - ``AgentListener`` / ``BeforeAgentListener``
     - Work at a defined point in agent startup
   * - ``ConfigurableSamplerProvider``
     - A sampler selectable by name
   * - ``InstrumentationModule``
     - Instrumenting a library upstream does not cover. This additionally
       requires upstream's muzzle Gradle plugins; see CLAUDE.md.

Two rules that are not obvious and are not enforced by the compiler:

1. **Dependencies in** ``custom/`` **are** ``compileOnly``. The agent supplies
   them at runtime. Bundling a second copy breaks class loader isolation.
2. **If your provider competes with an upstream one, implement** ``Ordered``.
   Resource providers are applied in ascending order and later ones win. See the
   comment on ``DistroResourceProvider.order()`` for what happens when this is
   missed.


Changelog
=========

``CHANGELOG.rst`` is not edited by hand. Add one entry file per user-facing
change:

.. code-block:: bash

    make chlog-new        # creates .chloggen/<branch>.yaml
    make chlog-validate   # what CI checks
    make chlog-preview    # render without writing

Changes that do not affect users (refactors, CI, formatting) do not need an
entry; start the pull request title with ``chore`` instead.


Upgrading the upstream agent
============================

The pin lives in one place, ``gradle/libs.versions.toml``. Renovate opens the
bump automatically. Before merging one, check that ``./gradlew build`` still
passes: the smoke tests are what catch an upstream change that silently breaks
the packaging or the resource-provider ordering.
