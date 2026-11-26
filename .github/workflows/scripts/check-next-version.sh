#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

released="${1-}"
requested="${2-}"

if [ -z "$requested" ]; then
  printf 'no next version was requested, so the next minor will be opened\n'
  exit 0
fi

scripts="$(cd "$(dirname "$0")" && pwd)"

printf 'subject\n\nNext-Version: %s\n' "$requested" \
  | "$scripts/next-snapshot-version.sh" "$released" > /dev/null

printf '%s will be opened on main once %s is published\n' "$requested" "$released"
