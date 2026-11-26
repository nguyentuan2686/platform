#!/usr/bin/env bash
# Tests for scripts/check-action-pins.sh. Prints "ok <case>" per case; exits non-zero on first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/check-action-pins.sh"
data="$root/scripts/testdata/action-pins"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

good_file_passes() {
  bash "$script" "$data/good.yml" >"$tmp/out" 2>&1 || fail "${FUNCNAME[0]}" "expected exit 0, got $?: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

tag_is_rejected() {
  local rc=0
  bash "$script" "$data/bad-tag.yml" >"$tmp/out" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc"
  grep -q "^PIN: .*bad-tag.yml:" "$tmp/out" || fail "${FUNCNAME[0]}" "missing PIN line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

short_sha_is_rejected() {
  local rc=0
  bash "$script" "$data/bad-short-sha.yml" >"$tmp/out" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc"
  grep -q "^PIN: .*bad-short-sha.yml:" "$tmp/out" || fail "${FUNCNAME[0]}" "missing PIN line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

good_file_passes
tag_is_rejected
short_sha_is_rejected
