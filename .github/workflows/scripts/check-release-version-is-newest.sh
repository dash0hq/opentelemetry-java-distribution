#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

version="${1-}"

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::release version '$version' does not match X.Y.Z" >&2
  exit 1
fi

published="$(sed -n 's|^.*refs/tags/v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$|\1|p' \
  | sort -V -u)"

if [ -z "$published" ]; then
  printf 'no release is published yet, so %s is the first\n' "$version"
  exit 0
fi

if printf '%s\n' "$published" | grep -qxF "$version"; then
  echo "::error::v$version is already tagged. Pick a higher version; a published" \
    "version is never re-cut." >&2
  exit 1
fi

highest="$(printf '%s\n' "$published" | tail -n 1)"

if [ "$(printf '%s\n%s\n' "$highest" "$version" | sort -V | tail -n 1)" != "$version" ]; then
  echo "::error::release version '$version' is older than the highest published" \
    "version $highest. release.yml marks every publish as latest, so $version" \
    "would displace $highest for anything reading the Releases API, and the tag" \
    "ruleset forbids deleting it afterwards." >&2
  exit 1
fi

printf '%s is newer than the highest published version %s\n' "$version" "$highest"
