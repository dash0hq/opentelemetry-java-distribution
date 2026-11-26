#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

released="${1-}"

if ! [[ "$released" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]]; then
  echo "::error::released version '$released' does not match X.Y.Z" >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"

requested="$(git interpret-trailers --parse | sed -n 's/^Next-Version: *//p' | tail -n 1)"

if [ -z "$requested" ]; then
  printf '%s.%s.0-SNAPSHOT\n' "$major" "$((minor + 1))"
  exit 0
fi

if ! [[ "$requested" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  echo "::error::the Next-Version trailer reads '$requested', which does not" \
    "match X.Y.Z-SNAPSHOT" >&2
  exit 1
fi

base="${requested%-SNAPSHOT}"
highest="$(printf '%s\n%s\n' "$base" "$released" | sort -V | tail -n 1)"

if [ "$base" = "$released" ] || [ "$highest" != "$base" ]; then
  echo "::error::the Next-Version trailer reads '$requested', which is not newer" \
    "than the released version $released" >&2
  exit 1
fi

printf '%s\n' "$requested"
