#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

requested="${1-}"

if [ -z "$requested" ]; then
  date -u +%Y-%m-%d
  exit 0
fi

if ! [[ "$requested" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "::error::release date '$requested' does not match YYYY-MM-DD" >&2
  exit 1
fi

if ! normalised="$(date -u -d "$requested" +%Y-%m-%d 2> /dev/null)" \
  || [ "$normalised" != "$requested" ]; then
  echo "::error::release date '$requested' is not a calendar date" >&2
  exit 1
fi

printf '%s\n' "$requested"
