#!/usr/bin/env bash
# Tests for the `gate` job script of .github/workflows/cd.yml (extracted with yq and run offline), since cd.yml itself
# only runs on main. Gating rule: missing secrets -> ::notice:: + exit 0 + cd=false; partial -> ::error:: + exit 1;
# only a successful push/dispatch ci run on this repo's main yields a sha.
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
sha=0123456789abcdef0123456789abcdef01234567

fail() { echo "FAIL $1: $2" >&2; exit 1; }

yq '.jobs.gate.steps[] | select(.id == "gate") | .run' "$root/.github/workflows/cd.yml" >"$tmp/gate.sh"
[ -s "$tmp/gate.sh" ] || fail extract "no gate step script in cd.yml"

run() { # run VAR=value...: defaults are a successful ci push on main with nothing configured; sets rc, out, $tmp/gh_out
  rc=0
  : >"$tmp/gh_out"
  env -i PATH="$PATH" GITHUB_OUTPUT="$tmp/gh_out" \
    EVENT=workflow_run REF=refs/heads/main REPO=o/platform \
    RUN_CONCLUSION=success RUN_EVENT=push RUN_HEAD_BRANCH=main RUN_HEAD_REPO=o/platform RUN_HEAD_SHA="$sha" INPUT_SHA= \
    HAS_CD_APP_ID=false HAS_CD_APP_PRIVATE_KEY=false \
    HAS_TS_OAUTH_CLIENT_ID=false HAS_TS_OAUTH_SECRET=false HAS_PROD_KUBECONFIG=false \
    "$@" bash "$tmp/gate.sh" >"$tmp/out" 2>&1 || rc=$?
}
outv() { sed -n "s/^$1=//p" "$tmp/gh_out"; }

nothing_configured_skips_with_notice() {
  run
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx '::notice::CD skipped: CD_APP_ID not set (see docs/runbooks/go-live.md)' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  grep -qx '::notice::verify-deploy skipped: TS_OAUTH_CLIENT_ID not set (see docs/runbooks/go-live.md)' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  [ "$(outv cd)" = false ] && [ "$(outv verify)" = false ] || fail "${FUNCNAME[0]}" "outputs: $(cat "$tmp/gh_out")"
  echo "ok ${FUNCNAME[0]}"
}

fully_configured_runs() {
  run HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true HAS_TS_OAUTH_CLIENT_ID=true HAS_TS_OAUTH_SECRET=true HAS_PROD_KUBECONFIG=true
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(outv cd)" = true ] && [ "$(outv verify)" = true ] && [ "$(outv sha)" = "$sha" ] || fail "${FUNCNAME[0]}" "outputs: $(cat "$tmp/gh_out")"
  echo "ok ${FUNCNAME[0]}"
}

partial_cd_config_is_an_error() {
  run HAS_CD_APP_ID=true
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out")"
  grep -q '^::error::CD is partially configured: CD_APP_ID set but CD_APP_PRIVATE_KEY not set' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

partial_verify_config_is_an_error() {
  run HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true HAS_PROD_KUBECONFIG=true
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out")"
  grep -q '^::error::verify-deploy is partially configured: PROD_KUBECONFIG set but TS_OAUTH_CLIENT_ID TS_OAUTH_SECRET not set' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

untrusted_or_failed_runs_are_skipped() {
  local v
  for v in RUN_CONCLUSION=failure RUN_EVENT=pull_request RUN_HEAD_BRANCH=feat/x RUN_HEAD_REPO=fork/platform; do
    run HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true "$v"
    [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "$v: rc=$rc: $(cat "$tmp/out")"
    [ "$(outv cd)" = false ] && [ -z "$(outv sha)" ] || fail "${FUNCNAME[0]}" "$v: outputs: $(cat "$tmp/gh_out")"
    grep -q '^::notice::CD skipped: ci run is not a successful push/dispatch' "$tmp/out" || fail "${FUNCNAME[0]}" "$v: $(cat "$tmp/out")"
  done
  run HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true RUN_EVENT=workflow_dispatch # ci publish_all on main
  [ "$rc" -eq 0 ] && [ "$(outv cd)" = true ] || fail "${FUNCNAME[0]}" "ci dispatch not accepted: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

dispatch_validates_sha_and_ref() {
  run EVENT=workflow_dispatch INPUT_SHA="$sha" HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true
  [ "$rc" -eq 0 ] && [ "$(outv sha)" = "$sha" ] || fail "${FUNCNAME[0]}" "valid dispatch: rc=$rc: $(cat "$tmp/out")"
  run EVENT=workflow_dispatch INPUT_SHA='abc;rm -rf /' HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true
  [ "$rc" -eq 1 ] && grep -q '^::error::sha must be 40 lowercase hex' "$tmp/out" || fail "${FUNCNAME[0]}" "bad sha: rc=$rc: $(cat "$tmp/out")"
  run EVENT=workflow_dispatch REF=refs/heads/feat/x INPUT_SHA="$sha" HAS_CD_APP_ID=true HAS_CD_APP_PRIVATE_KEY=true
  [ "$rc" -eq 1 ] && grep -q '^::error::cd must be dispatched on main' "$tmp/out" || fail "${FUNCNAME[0]}" "branch dispatch: rc=$rc: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

prod_secrets_only_in_the_prod_environment() {
  # Final review I6: the CD and verify-deploy secrets live in the GitHub Environment `prod` (deployment branch main
  # only). Every job of every workflow that names one of them must declare `environment: prod`, or it would see none
  # (the gate would always say "not set") and a branch workflow must never be able to read them.
  local wf job bad=""
  for wf in "$root"/.github/workflows/*.yml "$root"/.github/workflows/*.yaml; do
    [ -f "$wf" ] || continue
    while IFS=$'\t' read -r job env; do
      [ -n "$job" ] || continue
      [ "$env" = prod ] || bad+=" $(basename "$wf"):$job(environment=$env)"
    done < <(yq -o=json '.' "$wf" | jq -r '.jobs | to_entries[] | select((.value | tostring) | test("CD_APP_PRIVATE_KEY|CD_APP_ID|TS_OAUTH_CLIENT_ID|TS_OAUTH_SECRET|PROD_KUBECONFIG"))
      | [.key, (if (.value.environment | type) == "object" then .value.environment.name else (.value.environment // "none") end)] | @tsv')
  done
  [ -z "$bad" ] || fail "${FUNCNAME[0]}" "jobs using prod secrets without environment: prod:$bad"
  for job in gate bump verify-deploy; do
    [ "$(yq -r ".jobs[\"$job\"].environment" "$root/.github/workflows/cd.yml")" = prod ] || fail "${FUNCNAME[0]}" "cd.yml $job has no environment: prod"
  done
  echo "ok ${FUNCNAME[0]}"
}

nothing_configured_skips_with_notice
fully_configured_runs
partial_cd_config_is_an_error
partial_verify_config_is_an_error
untrusted_or_failed_runs_are_skipped
dispatch_validates_sha_and_ref
prod_secrets_only_in_the_prod_environment
