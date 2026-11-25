#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

version="${1-}"

if [ -z "$version" ]; then
  echo "::error::no version was given to look for in CHANGELOG.rst" >&2
  exit 1
fi

if [ ! -f CHANGELOG.rst ]; then
  echo "::error::no CHANGELOG.rst in $PWD" >&2
  exit 1
fi

if grep -qE "^${version//./\\.} " CHANGELOG.rst; then
  echo "::error::CHANGELOG.rst already has a section for $version, so main was" \
    "prepared for this version once already and never tagged. Folding a second" \
    "section would hide the first one's entries: release.yml reads the first" \
    "matching heading and stops at the next one. Push the tag v$version by hand," \
    "or revert the earlier preparation commit, instead of preparing it twice." >&2
  exit 1
fi

printf 'CHANGELOG.rst has no section for %s yet\n' "$version"
