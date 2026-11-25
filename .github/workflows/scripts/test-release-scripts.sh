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

create_tag_for_release="$scripts/create-tag-for-release.sh"

build_a_repository_whose_head_message_is() {
  local message="$1" already_tagged="${2-}"
  local root="$sandbox/tagging"
  rm -rf "$root"
  mkdir -p "$root"
  git init --quiet --bare "$root/origin.git"
  git init --quiet --initial-branch=main "$root/work"
  git -C "$root/work" config user.name releasing-test
  git -C "$root/work" config user.email releasing-test@example.invalid
  git -C "$root/work" config commit.gpgsign false
  git -C "$root/work" config tag.gpgsign false
  git -C "$root/work" remote add origin "$root/origin.git"
  : > "$root/work/seed"
  git -C "$root/work" add seed
  git -C "$root/work" commit --quiet --message "$message"
  git -C "$root/work" push --quiet origin main
  if [ -n "$already_tagged" ]; then
    git -C "$root/work" tag "$already_tagged"
    git -C "$root/work" push --quiet origin "$already_tagged"
    git -C "$root/work" tag --delete "$already_tagged" > /dev/null
  fi
}

run_create_tag_for_release() {
  build_a_repository_whose_head_message_is "$1" "${2-}" > /dev/null 2>&1
  (cd "$sandbox/tagging/work" && "$create_tag_for_release")
}

tags_on_origin_after_create_tag_for_release() {
  run_create_tag_for_release "$1" > /dev/null 2>&1 || return 1
  git -C "$sandbox/tagging/origin.git" tag --list | tr '\n' ' ' | sed 's/ *$//'
}

assert_prints "create-tag-for-release tags the exact release subject" \
  "v0.2.0" tags_on_origin_after_create_tag_for_release \
  "docs: update changelog to prepare release v0.2.0"
assert_prints "create-tag-for-release tags a release commit that carries a body" \
  "v0.2.0" tags_on_origin_after_create_tag_for_release \
  "$(printf 'docs: update changelog to prepare release v0.2.0\n\nFolded 3 entries.\n')"
assert_prints "create-tag-for-release leaves an unprefixed version untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "docs: update changelog to prepare release 0.2.0"
assert_prints "create-tag-for-release leaves a v inside the version untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "docs: update changelog to prepare release 0.v2.0"
assert_prints "create-tag-for-release leaves an ordinary commit untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "fix(custom): stop the resource provider losing to upstream"
assert_prints "create-tag-for-release leaves a subject with a prefix untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "Revert \"docs: update changelog to prepare release v0.2.0\""
assert_prints "create-tag-for-release leaves a subject with a suffix untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "docs: update changelog to prepare release v0.2.0 (#21)"
assert_prints "create-tag-for-release leaves a four-part version untagged" \
  "" tags_on_origin_after_create_tag_for_release \
  "docs: update changelog to prepare release v0.2.0.1"
assert_rejects "create-tag-for-release refuses to retag a published version" \
  "already exists on origin" run_create_tag_for_release \
  "docs: update changelog to prepare release v0.2.0" "v0.2.0"

create_tag_for_release_against_an_unreachable_origin() {
  build_a_repository_whose_head_message_is \
    "docs: update changelog to prepare release v0.2.0" > /dev/null 2>&1
  git -C "$sandbox/tagging/work" remote set-url origin "$sandbox/tagging/absent.git"
  (cd "$sandbox/tagging/work" && "$create_tag_for_release")
}

tags_left_after_an_unreachable_origin() {
  create_tag_for_release_against_an_unreachable_origin > /dev/null 2>&1 || true
  git -C "$sandbox/tagging/work" tag --list | tr '\n' ' ' | sed 's/ *$//'
}

assert_rejects "create-tag-for-release fails when origin cannot be reached" \
  "" create_tag_for_release_against_an_unreachable_origin
assert_prints "create-tag-for-release tags nothing when origin cannot be reached" \
  "" tags_left_after_an_unreachable_origin

next_snapshot_version="$scripts/next-snapshot-version.sh"

next_snapshot_after_releasing() {
  printf '%s\n' "$2" | "$next_snapshot_version" "$1"
}

assert_prints "next-snapshot-version bumps the minor when nothing is requested" \
  "0.3.0-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "docs: update changelog to prepare release v0.2.0"
assert_prints "next-snapshot-version carries the minor past nine" \
  "1.10.0-SNAPSHOT" next_snapshot_after_releasing "1.9.3" \
  "docs: update changelog to prepare release v1.9.3"
assert_prints "next-snapshot-version resets the patch of a patch release" \
  "0.3.0-SNAPSHOT" next_snapshot_after_releasing "0.2.7" \
  "docs: update changelog to prepare release v0.2.7"
assert_prints "next-snapshot-version honours a Next-Version trailer" \
  "0.2.1-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'docs: update changelog to prepare release v0.2.0\n\nNext-Version: 0.2.1-SNAPSHOT\n')"
assert_prints "next-snapshot-version honours a trailer that skips ahead" \
  "1.0.0-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'docs: update changelog to prepare release v0.2.0\n\nNext-Version: 1.0.0-SNAPSHOT\n')"
assert_prints "next-snapshot-version ignores the phrase outside the last paragraph" \
  "0.3.0-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'docs: update changelog to prepare release v0.2.0\n\nNext-Version: 0.2.1-SNAPSHOT\n\nThat line is prose, not a trailer.\n')"
assert_rejects "next-snapshot-version rejects a trailer with prose appended" \
  "does not match X.Y.Z-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'subject\n\nNext-Version: 0.2.1-SNAPSHOT is what we discussed.\n')"
assert_rejects "next-snapshot-version rejects a trailer with no -SNAPSHOT" \
  "does not match X.Y.Z-SNAPSHOT" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'subject\n\nNext-Version: 0.2.1\n')"
assert_rejects "next-snapshot-version rejects a trailer that goes backwards" \
  "is not newer than the released version" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'subject\n\nNext-Version: 0.1.0-SNAPSHOT\n')"
assert_rejects "next-snapshot-version rejects a trailer equal to the release" \
  "is not newer than the released version" next_snapshot_after_releasing "0.2.0" \
  "$(printf 'subject\n\nNext-Version: 0.2.0-SNAPSHOT\n')"
assert_rejects "next-snapshot-version rejects a v-prefixed released version" \
  "does not match X.Y.Z" next_snapshot_after_releasing "v0.2.0" "subject"
assert_rejects "next-snapshot-version rejects an empty released version" \
  "does not match X.Y.Z" next_snapshot_after_releasing "" "subject"

check_pending_changelog_entries="$scripts/check-pending-changelog-entries.sh"

in_a_tree_whose_chloggen_holds() {
  local names="$1"
  shift
  rm -rf "${sandbox:?}/chloggen"
  mkdir -p "$sandbox/chloggen/.chloggen"
  local name
  for name in $names; do
    : > "$sandbox/chloggen/.chloggen/$name"
  done
  (cd "$sandbox/chloggen" && "$@")
}

assert_prints "check-pending-changelog-entries counts one real entry" \
  "pending changelog entries: 1" \
  in_a_tree_whose_chloggen_holds "config.yaml TEMPLATE.yaml summary.tmpl oss_18.yaml" \
  "$check_pending_changelog_entries"
assert_prints "check-pending-changelog-entries counts several real entries" \
  "pending changelog entries: 3" \
  in_a_tree_whose_chloggen_holds "config.yaml TEMPLATE.yaml a.yaml b.yaml c.yaml" \
  "$check_pending_changelog_entries"
assert_rejects "check-pending-changelog-entries rejects config and template alone" \
  "holds no pending entries" \
  in_a_tree_whose_chloggen_holds "config.yaml TEMPLATE.yaml summary.tmpl" \
  "$check_pending_changelog_entries"
assert_rejects "check-pending-changelog-entries rejects an empty directory" \
  "holds no pending entries" \
  in_a_tree_whose_chloggen_holds "" "$check_pending_changelog_entries"
assert_rejects "check-pending-changelog-entries ignores a non-yaml entry" \
  "holds no pending entries" \
  in_a_tree_whose_chloggen_holds "config.yaml TEMPLATE.yaml notes.md" \
  "$check_pending_changelog_entries"

printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
