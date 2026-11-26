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
  echo "::error::CHANGELOG.rst already has a section for $version. Folding a" \
    "second section under that heading would hide the first one's entries:" \
    "release.yml reads the first matching heading and stops at the next one." \
    "If v$version is already published, pick a higher version. If main was" \
    "prepared for $version and never tagged, push the tag v$version by hand or" \
    "revert the preparation commit, instead of preparing it twice." >&2
  exit 1
fi

printf 'CHANGELOG.rst has no section for %s yet\n' "$version"
