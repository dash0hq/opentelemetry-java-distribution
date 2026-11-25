#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

version="${1-}"

if [ -z "$version" ]; then
  echo "::error::no version was given to write into gradle.properties" >&2
  exit 1
fi

if [ ! -f gradle.properties ]; then
  echo "::error::no gradle.properties in $PWD" >&2
  exit 1
fi

before="$(sed -n 's/^version *= *//p' gradle.properties)"

if [ -z "$before" ]; then
  echo "::error::gradle.properties has no 'version =' line" >&2
  exit 1
fi

sed -i "s|^version *=.*|version = $version|" gradle.properties

after="$(sed -n 's/^version *= *//p' gradle.properties)"

if [ "$after" != "$version" ]; then
  echo "::error::gradle.properties declares '$after' after being set to '$version'" >&2
  exit 1
fi

printf 'gradle.properties: %s -> %s\n' "$before" "$after"
