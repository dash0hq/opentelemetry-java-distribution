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

Nothing has been released from this repository yet.

The marker below is where ``make chlog-update`` inserts each new section.
Removing it makes that command fail with "expected one instance of
<!-- next version -->", which is why it is a reStructuredText comment rather
than ordinary prose.

.. <!-- next version -->

0.0.1 - 2026-10-23
==================

Not a release, and not installable. This tag exists only to run
``.github/workflows/release.yml`` from end to end once before the first real
release is cut, so that the workflow is known to work rather than assumed to.
The jars attached to it are an ordinary build of ``main`` and are unsupported.
Both the tag and this section are removed once the run has been inspected.

