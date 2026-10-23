=========
Releasing
=========

.. note::

   Nothing has been released from this repository yet. The process below is
   automated and has been exercised piece by piece, but no tag has been pushed.


Where the artifacts go
======================

GitHub release assets, and nowhere else. There is no Maven Central publication
and no other registry.

The consumer that decides this is the Dash0 Linux package in `dash0-packages
<https://github.com/dash0hq/dash0-packages>`_, which fetches the agent jar from
the GitHub Releases API and verifies it against the digest that API reports for
the asset. A release asset is therefore the published artifact rather than a
convenience copy of one.

Each release carries three assets:

- ``dash0-opentelemetry-javaagent-<version>.jar``, the product.
- ``dash0-opentelemetry-agentextension-<version>.jar``, the same code as an
  extension for the stock upstream agent.
- ``SHA256SUMS``, covering both jars. This is for a human checking a manual
  download. It is not what the package fetcher trusts, which is the API digest.

Build provenance is attested for both jars with
``actions/attest-build-provenance``.


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

1. Compile the pending changelog entries into a new section. The heading text
   is what the release workflow searches CHANGELOG.rst for, so it has to start
   with the version:

   .. code-block:: bash

       make chlog-update VERSION="0.2.0 - 2026-10-01"

2. Set the new version in ``gradle.properties``, dropping ``-SNAPSHOT``.
3. Open a pull request with those two changes. Merge it once CI is green.
4. Tag the merge commit ``v<version>`` and push the tag.
5. Set the next development version in ``gradle.properties``, restoring
   ``-SNAPSHOT``.

Pushing the tag in step 4 is what publishes. ``.github/workflows/release.yml``
then runs, and refuses to continue unless three things hold:

- The tag matches ``vX.Y.Z``.
- The tagged commit is reachable from ``origin/main``. Without this, tag-push
  permission alone would be enough to publish an unreviewed commit.
- ``gradle.properties`` declares exactly the version the tag names, so the jar
  filenames cannot disagree with the release they hang off.

It then runs the full build, collects the assets, attaches them to a **draft**
release, and publishes the release as its last action. A published release
therefore always has its complete asset set. Re-running against an
already-published tag fails rather than modifying it; burn the version and
re-tag instead.

The release notes are the CHANGELOG.rst section for the version, converted to
Markdown. A tag with no matching section fails the workflow.


What is not automated
=====================

The documentation sync. ``.github/workflows/sync-docs.yml`` runs in dry-run
mode on any pull request touching ``docs/``, which needs no credentials, but a
real sync needs three repository secrets that do not exist on this repository:
``DASH0_WEBSITE_SYNC_TOKEN``, ``SYNC_DOCUMENTATION_TARGET_REPOSITORY`` and
``SYNC_DOCUMENTATION_TARGET_DIRECTORY``. They are repository-level secrets on
the Ruby distribution, not organisation-level, so someone with administrator
access has to create them here.

Until they exist, run the sync by hand from the Actions tab with ``dry-run``
unchecked. Once they exist, chain it off the release the way the Ruby
distribution does, with a ``workflow_call`` trigger here and a ``needs:`` job
in ``release.yml``.


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

Both are at 2.32.0 today.

Nothing enforces that they agree, and they are bumped by different people on
different schedules. Once this distribution is what the operator ships, the
operator's pom stops naming the agent at all and the coupling disappears. Until
then, moving the pin here is only half the change: check the operator's pin in
the same breath, and say in the pull request description whether it also needs
to move.
