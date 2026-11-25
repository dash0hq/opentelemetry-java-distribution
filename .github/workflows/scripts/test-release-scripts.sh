#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0

set -uo pipefail

scripts="$(cd "$(dirname "$0")" && pwd)"
passed=0
failed=0

pass() {
  passed=$((passed + 1))
  printf 'ok    %s\n' "$1"
}

fail() {
  failed=$((failed + 1))
  printf 'FAIL  %s\n      %s\n' "$1" "$2"
}

assert_prints() {
  local label="$1" expected="$2"
  shift 2
  local actual
  if ! actual="$("$@" 2>&1)"; then
    fail "$label" "expected success, got a non-zero exit with: $actual"
    return
  fi
  if [ "$actual" != "$expected" ]; then
    fail "$label" "expected '$expected', got '$actual'"
    return
  fi
  pass "$label"
}

assert_rejects() {
  local label="$1" needle="$2"
  shift 2
  local actual
  if actual="$("$@" 2>&1)"; then
    fail "$label" "expected a non-zero exit, got success with: $actual"
    return
  fi
  if [[ "$actual" != *"$needle"* ]]; then
    fail "$label" "expected the message to contain '$needle', got: $actual"
    return
  fi
  pass "$label"
}

check_release_version="$scripts/check-release-version.sh"

assert_prints "check-release-version accepts 0.2.0" \
  "0.2.0" "$check_release_version" "0.2.0"
assert_prints "check-release-version accepts a two-digit minor" \
  "1.10.3" "$check_release_version" "1.10.3"
assert_rejects "check-release-version rejects a v prefix" \
  "must not start with v" "$check_release_version" "v0.2.0"
assert_rejects "check-release-version rejects a two-part version" \
  "does not match X.Y.Z" "$check_release_version" "0.2"
assert_rejects "check-release-version rejects a -SNAPSHOT suffix" \
  "does not match X.Y.Z" "$check_release_version" "0.2.0-SNAPSHOT"
assert_rejects "check-release-version rejects an empty version" \
  "no release version was given" "$check_release_version" ""
assert_rejects "check-release-version rejects a missing argument" \
  "no release version was given" "$check_release_version"
assert_rejects "check-release-version rejects trailing text" \
  "does not match X.Y.Z" "$check_release_version" "0.2.0 - 2026-11-24"

resolve_release_date="$scripts/resolve-release-date.sh"

assert_prints "resolve-release-date keeps a valid date" \
  "2026-11-24" "$resolve_release_date" "2026-11-24"
assert_prints "resolve-release-date defaults to today when empty" \
  "$(date -u +%Y-%m-%d)" "$resolve_release_date" ""
assert_prints "resolve-release-date defaults to today when absent" \
  "$(date -u +%Y-%m-%d)" "$resolve_release_date"
assert_rejects "resolve-release-date rejects a non-ISO date" \
  "does not match YYYY-MM-DD" "$resolve_release_date" "24/11/2026"
assert_rejects "resolve-release-date rejects an impossible day" \
  "is not a calendar date" "$resolve_release_date" "2026-02-31"
assert_rejects "resolve-release-date rejects an impossible month" \
  "is not a calendar date" "$resolve_release_date" "2026-13-01"

set_gradle_version="$scripts/set-gradle-version.sh"

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT

in_a_tree_whose_gradle_properties_is() {
  local contents="$1"
  shift
  rm -rf "${sandbox:?}/tree"
  mkdir -p "$sandbox/tree"
  if [ "$contents" != "<absent>" ]; then
    printf '%s' "$contents" > "$sandbox/tree/gradle.properties"
  fi
  (cd "$sandbox/tree" && "$@")
}

set_version_and_show_the_file() {
  in_a_tree_whose_gradle_properties_is "$1" "$set_gradle_version" "$2" > /dev/null || return 1
  cat "$sandbox/tree/gradle.properties"
}

assert_prints "set-gradle-version drops -SNAPSHOT and leaves the rest alone" \
  "$(printf 'group = com.dash0.opentelemetry\nversion = 0.2.0\n\norg.gradle.parallel = true')" \
  set_version_and_show_the_file \
  "$(printf 'group = com.dash0.opentelemetry\nversion = 0.2.0-SNAPSHOT\n\norg.gradle.parallel = true\n')" \
  "0.2.0"
assert_prints "set-gradle-version writes a -SNAPSHOT version" \
  "version = 0.3.0-SNAPSHOT" \
  set_version_and_show_the_file "$(printf 'version = 0.2.0\n')" "0.3.0-SNAPSHOT"
assert_prints "set-gradle-version tolerates no spaces around the equals sign" \
  "version = 0.2.0" \
  set_version_and_show_the_file "$(printf 'version=0.1.0-SNAPSHOT\n')" "0.2.0"

assert_rejects "set-gradle-version rejects a tree with no gradle.properties" \
  "no gradle.properties" \
  in_a_tree_whose_gradle_properties_is "<absent>" "$set_gradle_version" "0.2.0"
assert_rejects "set-gradle-version rejects a gradle.properties with no version line" \
  "has no 'version =' line" \
  in_a_tree_whose_gradle_properties_is "group = com.dash0.opentelemetry" \
  "$set_gradle_version" "0.2.0"
assert_rejects "set-gradle-version rejects an empty version" \
  "no version was given" \
  in_a_tree_whose_gradle_properties_is "version = 0.2.0" "$set_gradle_version" ""

printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
