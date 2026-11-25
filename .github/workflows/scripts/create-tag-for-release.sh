#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

subject="$(git log -1 --pretty=%s)"
pattern='^docs: update changelog to prepare release (v[0-9]+\.[0-9]+\.[0-9]+)$'

if ! [[ "$subject" =~ $pattern ]]; then
  echo "HEAD is not a release commit, so there is no tag to create."
  echo "Its subject line is: $subject"
  exit 0
fi

tag="${BASH_REMATCH[1]}"

if [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then
  echo "::error::$tag already exists on origin, so HEAD prepares a release that" \
    "has already been cut. Nothing was tagged." >&2
  exit 1
fi

git tag "$tag"
git push origin "refs/tags/$tag"
echo "Pushed $tag, which starts release.yml."
