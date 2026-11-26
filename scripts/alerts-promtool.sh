#!/usr/bin/env bash
# Usage: scripts/alerts-promtool.sh [RULES_YAML] [TESTS_DIR]
#   defaults: deploy/k8s/platform/grafana/alerting/rules.yaml, scripts/testdata/alerts
# Unit-tests the alert expressions with promtool. Each Grafana rule (data[refId=A].model.expr, for, labels) is converted
# into a Prometheus alerting rule (alert: <uid>) in a temp dir, together with a copy of TESTS_DIR/*.test.yaml, whose
# `rule_files` name rules.generated.yaml. Runs the pinned promtool image; yq + jq locally if installed, else in a pinned image.
# Needs: docker. Prints `OK alerts-promtool` and exits 0, or the promtool output and exits non-zero.
set -euo pipefail
cd "$(dirname "$0")/.."

PROMETHEUS_IMAGE="prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996"
TOOLS_IMAGE="alpine/k8s:1.33.4@sha256:b0523f0a244ddc4c8e055aa335c040d3d78b3ead5528f4544395f7f9f69c7b68" # yq + jq
RULES="${1:-deploy/k8s/platform/grafana/alerting/rules.yaml}"
TESTS="${2:-scripts/testdata/alerts}"
export MSYS_NO_PATHCONV=1 # Git Bash on Windows must not rewrite the container paths below

[ -f "$RULES" ] || { echo "FAIL alerts-promtool: $RULES not found" >&2; exit 1; }
shopt -s nullglob
tests=("$TESTS"/*.test.yaml)
[ ${#tests[@]} -gt 0 ] || { echo "FAIL alerts-promtool: no $TESTS/*.test.yaml" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mount="$tmp"
if command -v cygpath >/dev/null 2>&1; then mount="$(cygpath -m "$tmp")"; fi

# One JSON document is valid YAML, so promtool reads it as it is.
JQ_CONVERT='{groups: [{name: "platform", rules: [.groups[].rules[]
  | {alert: .uid, expr: (.data[] | select(.refId == "A") | .model.expr), for: .for, labels: (.labels // {})}]}]}'
if command -v yq >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  yq -o=json '.' "$RULES" | jq "$JQ_CONVERT" >"$tmp/rules.generated.yaml"
else
  rules_abs="$(cd "$(dirname "$RULES")" && pwd)"
  if command -v cygpath >/dev/null 2>&1; then rules_abs="$(cygpath -m "$rules_abs")"; fi
  docker run --rm -v "$rules_abs:/in:ro" "$TOOLS_IMAGE" \
    sh -c "yq -o=json '.' /in/$(basename "$RULES") | jq '$JQ_CONVERT'" >"$tmp/rules.generated.yaml"
fi
[ -s "$tmp/rules.generated.yaml" ] || { echo "FAIL alerts-promtool: conversion of $RULES produced nothing" >&2; exit 1; }
cp "${tests[@]}" "$tmp/"
chmod -R a+rX "$tmp" # the promtool image runs as nobody; mktemp -d is 0700

docker run --rm --entrypoint promtool -v "$mount:/work:ro" -w /work "$PROMETHEUS_IMAGE" check rules rules.generated.yaml
files=()
for t in "${tests[@]}"; do files+=("$(basename "$t")"); done
docker run --rm --entrypoint promtool -v "$mount:/work:ro" -w /work "$PROMETHEUS_IMAGE" test rules "${files[@]}"
echo "OK alerts-promtool"
