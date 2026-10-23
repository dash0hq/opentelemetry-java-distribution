#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0
#
# Fails when buildSrc/src/main/kotlin/Relocations.kt has drifted from upstream's
# canonical copy at the agent version this distribution pins.
#
# The relocation rules decide which packages are rewritten into the agent's
# shaded namespace. Upstream changes them when it moves a package, and a rule we
# failed to copy produces no error: the agent starts, and whatever depended on
# the moved package is silently never invoked. The two exclusions are the sharp
# edge, because a relocated resource provider is simply never called.
#
# Comments and the licence header are ignored. Only the relocate and exclude
# calls are compared.
#
#     scripts/check-relocations.sh

set -euo pipefail

cd "$(dirname "$0")/.."

OURS=buildSrc/src/main/kotlin/Relocations.kt
VERSION="$(sed -n 's/^opentelemetryJavaagent = "\(.*\)"$/\1/p' gradle/libs.versions.toml)"

if [ -z "$VERSION" ]; then
  echo "Could not read opentelemetryJavaagent from gradle/libs.versions.toml" >&2
  exit 1
fi

URL="https://raw.githubusercontent.com/open-telemetry/opentelemetry-java-instrumentation/v${VERSION}/examples/distro/buildSrc/src/main/kotlin/Relocations.kt"

upstream="$(mktemp)"
trap 'rm -f "$upstream"' EXIT

if ! curl --fail --silent --show-error --location "$URL" --output "$upstream"; then
  echo "Could not fetch upstream Relocations.kt for agent $VERSION from $URL" >&2
  exit 1
fi

rules() {
  grep -oE 'shadowJar\.relocate\([^)]*\)|exclude\("[^"]*"\)' "$1" | tr -d ' \t'
}

upstream_rules="$(rules "$upstream" || true)"
our_rules="$(rules "$OURS" || true)"

# Two empty extractions compare equal, so without this the check would report a
# match while comparing nothing. That is reachable: if upstream restructures the
# file so the calls no longer read "shadowJar.relocate(", the diff fails, the
# obvious repair is to copy upstream's file across, and from then on both sides
# extract nothing and the check passes forever.
if [ -z "$upstream_rules" ]; then
  echo "Extracted no relocation rules from upstream's copy for agent $VERSION:" >&2
  echo "  $URL" >&2
  echo "The pattern in rules() no longer matches upstream's file. Update it." >&2
  exit 1
fi

if diff -u <(printf '%s\n' "$upstream_rules") <(printf '%s\n' "$our_rules"); then
  echo "Relocations.kt matches upstream at agent $VERSION."
else
  cat >&2 <<EOF

$OURS has drifted from upstream's copy for agent $VERSION.

  $URL

Lines marked - are upstream's and missing here; lines marked + are ours and not
upstream's. Copy upstream's rules across. A missing rule produces no error at
runtime, only instrumentation that never runs.
EOF
  exit 1
fi
