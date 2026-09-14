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

Not yet decided. The options observed in the vendor distributions are:

- **Release whenever the upstream pin moves.** Grafana does this: a scheduled job
  checks weekly whether the file holding the upstream version changed since the
  last release, and publishes if it did. No human decides when to ship.
- **Track upstream releases on a stated commitment.** Splunk publishes within
  five working days of each upstream instrumentation release.
- **Pin deliberately behind.** Amazon does this, with a toggle to test against
  upstream snapshots.

The nightly workflow in ``.github/workflows/nightly.yml`` already builds against
the latest upstream release, which is the prerequisite for any of these.
