#!/usr/bin/env bash
# Tests for infra/bootstrap/verify.sh without a cluster: a failing fake kubectl stands in for "no cluster".
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/infra/bootstrap/verify.sh"
data="$root/scripts/testdata/bootstrap-verify"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

mkdir -p "$tmp/bin"
printf '#!/bin/sh\necho "The connection to the server localhost:8080 was refused" >&2\nexit 1\n' >"$tmp/bin/kubectl"
chmod +x "$tmp/bin/kubectl"

run_verify() { # args...; sets rc, writes $tmp/out
  rc=0
  PATH="$tmp/bin:$PATH" VERIFY_DEADLINE_S=0 bash "$script" "$@" >"$tmp/out" 2>&1 || rc=$?
}

[ ! -x /usr/local/bin/k3s ] || { echo "skip: /usr/local/bin/k3s exists (a real cluster host)"; exit 0; }

no_cluster_fails_k3s_ready() {
  run_verify
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit: $(cat "$tmp/out")"
  grep -q '^FAIL k3s-ready: ' "$tmp/out" || fail "${FUNCNAME[0]}" "no 'FAIL k3s-ready': $(cat "$tmp/out")"
  grep -q '^FAIL argocd-ready: ' "$tmp/out" || fail "${FUNCNAME[0]}" "no 'FAIL argocd-ready': $(cat "$tmp/out")"
  ! grep -q 'second-run-no-changes' "$tmp/out" || fail "${FUNCNAME[0]}" "second-run check ran without --expect-unchanged"
  echo "ok ${FUNCNAME[0]}"
}

changed_log_fails_second_run() {
  run_verify --expect-unchanged "$data/changed.log"
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL second-run-no-changes: CHANGED firewall: ufw rules$' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "no 'FAIL second-run-no-changes': $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

truncated_log_fails_second_run() {
  run_verify --expect-unchanged "$data/truncated.log"
  grep -q '^FAIL second-run-no-changes: last line is \[OK firewall\]' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "no 'FAIL second-run-no-changes' for a log without the summary: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unchanged_log_passes_second_run() {
  run_verify --expect-unchanged "$data/unchanged.log"
  grep -qx 'OK second-run-no-changes' "$tmp/out" || fail "${FUNCNAME[0]}" "no 'OK second-run-no-changes': $(cat "$tmp/out")"
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit (no cluster)"
  echo "ok ${FUNCNAME[0]}"
}

no_cluster_fails_k3s_ready
changed_log_fails_second_run
truncated_log_fails_second_run
unchanged_log_passes_second_run
