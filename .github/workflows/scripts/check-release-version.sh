#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

version="${1-}"

if [ -z "$version" ]; then
  echo "::error::no release version was given; expected X.Y.Z, for example 0.2.0" >&2
  exit 1
fi

if [ "${version:0:1}" = "v" ]; then
  echo "::error::release version '$version' must not start with v. The tag is" \
    "prefixed and the CHANGELOG.rst heading is not, and release.yml finds the" \
    "section by matching the heading, so a v here renders a section the release" \
    "cannot find. Drop it, for example 0.2.0" >&2
  exit 1
fi

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::release version '$version' does not match X.Y.Z, which is the" \
    "pattern release.yml enforces on the tag" >&2
  exit 1
fi

printf '%s\n' "$version"
