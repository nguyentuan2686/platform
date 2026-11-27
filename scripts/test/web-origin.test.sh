#!/usr/bin/env bash
# Tests for scripts/web-origin.sh and its wiring in ci.yml. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/web-origin.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL $1: $2" >&2; exit 1; }
domain_file() { printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-domain\ndata:\n  APP_DOMAIN: %s\n' "$1" >"$tmp/domain.yaml"; }

real_domain_gives_https_origin() {
  domain_file tuvi.example.com
  [ "$(bash "$script" "$tmp/domain.yaml")" = https://tuvi.example.com ] || fail "${FUNCNAME[0]}" "$(bash "$script" "$tmp/domain.yaml")"
  domain_file '"tuvi.example.com" # quoted, with a comment'
  [ "$(bash "$script" "$tmp/domain.yaml")" = https://tuvi.example.com ] || fail "${FUNCNAME[0]}" "quoted: $(bash "$script" "$tmp/domain.yaml")"
  echo "ok ${FUNCNAME[0]}"
}

invalid_placeholder_gives_empty() {
  domain_file app.example.invalid
  [ -z "$(bash "$script" "$tmp/domain.yaml")" ] || fail "${FUNCNAME[0]}" "$(bash "$script" "$tmp/domain.yaml")"
  [ -z "$(bash "$script")" ] || fail "${FUNCNAME[0]}" "the committed domain.yaml must still be the .invalid placeholder in this test"
  echo "ok ${FUNCNAME[0]}"
}

garbage_fails() {
  local rc=0
  domain_file 'https://x/y'
  bash "$script" "$tmp/domain.yaml" >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

ci_passes_it_to_the_web_build() {
  local ci="$root/.github/workflows/ci.yml"
  grep -q 'bash scripts/web-origin.sh' <<<"$(yq -r '.jobs.changes.steps[] | select(.id == "origin") | .run' "$ci")" \
    || fail "${FUNCNAME[0]}" "changes job has no origin step running scripts/web-origin.sh"
  [ "$(yq -r '.jobs.changes.outputs.web_origin' "$ci")" = '${{ steps.origin.outputs.web_origin }}' ] || fail "${FUNCNAME[0]}" "no web_origin output"
  [ "$(yq -r '.jobs.web.with["build-args"]' "$ci")" = 'WEB_ORIGIN=${{ needs.changes.outputs.web_origin }}' ] \
    || fail "${FUNCNAME[0]}" "web build-args: $(yq -r '.jobs.web.with["build-args"]' "$ci")"
  yq -r '.jobs.changes.steps[] | select(.id == "filter") | .with.filters' "$ci" | yq -r '.web[]' | grep -qx 'deploy/k8s/overlays/prod/domain/\*\*' \
    || fail "${FUNCNAME[0]}" "web paths filter misses deploy/k8s/overlays/prod/domain/**"
  local nf="$root/.github/workflows/_node-service.yml"
  [ "$(yq -r '.on.workflow_call.inputs["build-args"].type' "$nf")" = string ] || fail "${FUNCNAME[0]}" "_node-service has no build-args input"
  [ "$(yq -r '[.jobs[].steps[]? | select(.uses == "docker/build-push-action*") | .with["build-args"]] | unique | join(",")' "$nf")" = '${{ inputs.build-args }}' ] \
    || fail "${FUNCNAME[0]}" "not every docker build passes inputs.build-args"
  echo "ok ${FUNCNAME[0]}"
}

real_domain_gives_https_origin
invalid_placeholder_gives_empty
garbage_fails
ci_passes_it_to_the_web_build
