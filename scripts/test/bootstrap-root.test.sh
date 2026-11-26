#!/usr/bin/env bash
# Tests for render_root (infra/bootstrap/lib/argocd.sh): the rendered root Application, and the child Applications
# that Argo CD would build from deploy/argocd/apps with the root's kustomize patches (kubectl kustomize, offline).
# Needs kubectl and yq. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
sha=0123456789abcdef0123456789abcdef01234567

fail() { echo "FAIL $1: $2" >&2; exit 1; }

render() { # render <revision> <ci-mode> [repo-url] -> $tmp/root.yaml
  (
    # shellcheck disable=SC2034 # read by the sourced lib/argocd.sh
    BOOTSTRAP_DIR="$root/infra/bootstrap" REPO_ROOT="$root"
    # shellcheck source=../../infra/bootstrap/lib/common.sh
    . "$root/infra/bootstrap/lib/common.sh"
    # shellcheck source=../../infra/bootstrap/lib/argocd.sh
    . "$root/infra/bootstrap/lib/argocd.sh"
    # shellcheck disable=SC2034 # read by render_root
    GITOPS_REVISION="$1" GITOPS_CI_MODE="$2" GITOPS_REPO_URL="${3:-$DEFAULT_GITOPS_REPO_URL}"
    render_root
  ) >"$tmp/root.yaml"
}

# children: build deploy/argocd/apps with the rendered root's patches, as Argo CD does -> $tmp/children.yaml
children() {
  rm -rf "$tmp/k" && mkdir -p "$tmp/k" && cp "$root"/deploy/argocd/apps/*.yaml "$tmp/k/"
  if [ "$(yq '.spec.source.kustomize.patches // [] | length' "$tmp/root.yaml")" -gt 0 ]; then
    yq -i ".patches = load(\"$tmp/root.yaml\").spec.source.kustomize.patches" "$tmp/k/kustomization.yaml"
  fi
  kubectl kustomize "$tmp/k" >"$tmp/children.yaml"
}

app() { yq "select(.kind == \"Application\" and .metadata.name == \"$1\") | $2" "$tmp/children.yaml"; }

main_renders_unpatched() {
  render main 0
  [ "$(yq .spec.source.targetRevision "$tmp/root.yaml")" = main ] || fail "${FUNCNAME[0]}" "targetRevision"
  [ "$(yq '.spec.source.kustomize // "none"' "$tmp/root.yaml")" = none ] || fail "${FUNCNAME[0]}" "unexpected kustomize patches"
  diff <(tr -d '\r' <"$root/deploy/argocd/root.yaml") "$tmp/root.yaml" >/dev/null || fail "${FUNCNAME[0]}" "differs from root.yaml"
  echo "ok ${FUNCNAME[0]}"
}

ci_mode_pins_children_and_stops_tunnel_and_apps() {
  local a
  render "$sha" 1
  [ "$(yq .spec.source.targetRevision "$tmp/root.yaml")" = "$sha" ] || fail "${FUNCNAME[0]}" "root targetRevision"
  [ "$(yq '.spec.syncPolicy.automated.prune' "$tmp/root.yaml")" = true ] || fail "${FUNCNAME[0]}" "root lost automated sync"
  children
  for a in prod-sealed prod-platform prod-tunnel prod-apps; do
    [ "$(app "$a" .spec.source.targetRevision)" = "$sha" ] || fail "${FUNCNAME[0]}" "$a targetRevision $(app "$a" .spec.source.targetRevision)"
  done
  for a in prod-sealed prod-platform; do
    [ "$(app "$a" '.spec.syncPolicy.automated.selfHeal')" = true ] || fail "${FUNCNAME[0]}" "$a lost automated sync"
    [ "$(app "$a" '.metadata.annotations["platform.dev/ci-manual-sync"] // "none"')" = none ] || fail "${FUNCNAME[0]}" "$a marked manual"
  done
  for a in prod-tunnel prod-apps; do
    [ "$(app "$a" '.spec.syncPolicy.automated // "none"')" = none ] || fail "${FUNCNAME[0]}" "$a still automated"
    [ "$(app "$a" '.metadata.annotations["platform.dev/ci-manual-sync"]')" = true ] || fail "${FUNCNAME[0]}" "$a not marked"
    [ "$(app "$a" '.metadata.annotations["argocd.argoproj.io/sync-wave"]')" = 1 ] || fail "${FUNCNAME[0]}" "$a lost its wave"
  done
  echo "ok ${FUNCNAME[0]}"
}

revision_without_ci_mode_keeps_automated() {
  local a
  render v9.9.9 0
  children
  for a in prod-sealed prod-platform prod-tunnel prod-apps; do
    [ "$(app "$a" .spec.source.targetRevision)" = v9.9.9 ] || fail "${FUNCNAME[0]}" "$a targetRevision"
    [ "$(app "$a" '.spec.syncPolicy.automated.prune')" = true ] || fail "${FUNCNAME[0]}" "$a lost automated sync"
  done
  echo "ok ${FUNCNAME[0]}"
}

other_repo_reaches_children_and_project() {
  render main 0 https://example.invalid/fork/platform.git
  children
  [ "$(app prod-apps .spec.source.repoURL)" = https://example.invalid/fork/platform.git ] || fail "${FUNCNAME[0]}" "child repoURL"
  [ "$(yq 'select(.kind == "AppProject") | .spec.sourceRepos[0]' "$tmp/children.yaml")" = https://example.invalid/fork/platform.git ] \
    || fail "${FUNCNAME[0]}" "AppProject sourceRepos"
  echo "ok ${FUNCNAME[0]}"
}

bad_revision_refused() {
  if (render 'main"; rm -rf /' 1) 2>"$tmp/err"; then fail "${FUNCNAME[0]}" "accepted a revision with quotes"; fi
  grep -q 'GITOPS_REVISION has unexpected characters' "$tmp/err" || fail "${FUNCNAME[0]}" "$(cat "$tmp/err")"
  echo "ok ${FUNCNAME[0]}"
}

project_forbids_plain_secrets() {
  render main 0
  children
  [ "$(yq 'select(.kind == "AppProject" and .metadata.name == "prod") | .spec.namespaceResourceBlacklist[] | select(.group == "" and .kind == "Secret") | .kind' "$tmp/children.yaml")" = Secret ] \
    || fail "${FUNCNAME[0]}" "AppProject prod does not blacklist Secret"
  for a in prod-sealed prod-platform prod-tunnel prod-apps; do
    [ "$(app "$a" .spec.project)" = prod ] || fail "${FUNCNAME[0]}" "$a is not in project prod"
  done
  echo "ok ${FUNCNAME[0]}"
}

main_renders_unpatched
ci_mode_pins_children_and_stops_tunnel_and_apps
revision_without_ci_mode_keeps_automated
other_repo_reaches_children_and_project
bad_revision_refused
project_forbids_plain_secrets
