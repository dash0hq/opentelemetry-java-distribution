#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

pending=0

for entry in .chloggen/*.yaml; do
  case "$entry" in
    '.chloggen/*.yaml' | .chloggen/config.yaml | .chloggen/TEMPLATE.yaml) continue ;;
  esac
  pending=$((pending + 1))
done

if [ "$pending" -eq 0 ]; then
  echo "::error::.chloggen/ holds no pending entries. chloggen would still write" \
    "a heading with nothing under it, and the release would publish with empty" \
    "notes under a tag that cannot be deleted." >&2
  exit 1
fi

printf 'pending changelog entries: %s\n' "$pending"
