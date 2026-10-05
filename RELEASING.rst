=========
Releasing
=========

.. note::

   Nothing has been released from this repository yet, and the artifacts are not
   published anywhere. Where they should be published, and under which
   coordinates, is one of the open decisions in `OSS-41
   <https://linear.app/dash0/issue/OSS-41/dash0-java-distro-analyse-approaches-to-creating-our-own-distro>`_.
   This document records the intended process so it is not invented under time
   pressure later.


Versioning
==========

The distribution version is independent of the upstream agent version. Both are
recorded in the agent jar manifest as
``Implementation-Version: dash0-<distro version>-otel-<upstream version>``, which
is the convention Splunk and Elastic use.

The distribution version lives in ``gradle.properties``. The upstream agent pin
lives in ``gradle/libs.versions.toml``.


Cutting a release
=================

1. Compile the pending changelog entries into a new section:

   .. code-block:: bash

       make chlog-update VERSION="0.2.0 - 2026-10-01"

2. Set the new version in ``gradle.properties``, dropping ``-SNAPSHOT``.
3. Open a pull request with those two changes. Merge it once CI is green.
4. Tag the merge commit ``v<version>`` and push the tag.
5. Set the next development version in ``gradle.properties``, restoring
   ``-SNAPSHOT``.


Cadence
=======

Release whenever the upstream pin moves, and otherwise only when there is
something to ship.

A distribution has to be re-released for every upstream agent release, whether
or not anything here changed, or it falls behind and stops being worth using.
Honeycomb's Java distribution was archived at upstream agent v1.33.1 for
substantially that reason. The corollary is that cutting a release must stay
cheap enough to do on upstream's cadence, which is roughly monthly.

Renovate opens the bump as its own pull request, grouped and never automerged,
because moving the upstream agent changes the entire bundled instrumentation set
and is a product decision rather than housekeeping. The nightly workflow in
``.github/workflows/nightly.yml`` already builds against the latest upstream
release, so a bump that would break the packaging is usually known before the
pull request exists.

The alternatives, for the record: Splunk commits to publishing within five
working days of each upstream instrumentation release, and Amazon pins
deliberately behind with a toggle for testing against upstream snapshots.


Keeping the operator in step
============================

The upstream agent version is pinned in **two** repositories, and they describe
the same agent:

- ``gradle/libs.versions.toml`` here, as ``opentelemetryJavaagent``.
- ``images/instrumentation/jvm/pom.xml`` in `dash0hq/dash0-operator
  <https://github.com/dash0hq/dash0-operator>`_, which the operator's
  instrumentation image uses to download the agent it injects.

Both are at 2.31.1 today.

Nothing enforces that they agree, and they are bumped by different people on
different schedules. Once this distribution is what the operator ships, the
operator's pom stops naming the agent at all and the coupling disappears. Until
then, moving the pin here is only half the change: check the operator's pin in
the same breath, and say in the pull request description whether it also needs
to move.
