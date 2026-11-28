#!/usr/bin/env bash
# Tests for scripts/check-system-docs.sh and its wiring in ci.yml. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/check-system-docs.sh"
fail() { echo "FAIL $1: $2" >&2; exit 1; }

# run <files, newline-separated> [pr body] [author]: sets rc and out
run() {
  rc=0
  out="$(printf '%s\n' "$1" | PR_BODY="${2:-}" PR_AUTHOR="${3:-someone}" bash "$script" 2>&1)" || rc=$?
}

watched_change_without_docs_fails() {
  local f
  for f in contracts/openapi/payment-svc.yaml deploy/k8s/overlays/prod/apps/ingress.yaml deploy/argocd/apps/prod-apps.yaml \
    infra/bootstrap/bootstrap.sh .github/workflows/cd.yml services/payment-svc/src/main/resources/db/migration/V3__x.sql \
    services/notify-svc/migrations/0002_x.sql services/web/src/app/api/orders/route.ts; do
    run "$f"
    [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "$f: rc=$rc"
    grep -q "NEEDS $f" <<<"$out" || fail "${FUNCNAME[0]}" "$f not named: $out"
  done
  echo "ok ${FUNCNAME[0]}"
}

watched_change_with_docs_passes() {
  run "$(printf 'deploy/k8s/overlays/prod/apps/ingress.yaml\ndocs/system/index.html')"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc $out"
  grep -q '^OK system-docs' <<<"$out" || fail "${FUNCNAME[0]}" "$out"
  echo "ok ${FUNCNAME[0]}"
}

unwatched_change_passes() {
  run "$(printf 'services/reading-tuvi/src/main/java/dev/platform/X.java\nREADME.md\nservices/web/src/app/vi/page.tsx')"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc $out"
  run ""
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "empty list: rc=$rc $out"
  echo "ok ${FUNCNAME[0]}"
}

image_tag_file_is_exempt() {
  run "deploy/k8s/overlays/prod/apps/kustomization.yaml"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc $out"
  echo "ok ${FUNCNAME[0]}"
}

opt_out_line_passes_only_with_reason() {
  run ".github/workflows/ci.yml" "$(printf 'Bump a pin.\n\ndocs-system: n/a only a comment changed\n')"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "with reason: rc=$rc $out"
  grep -q 'opt-out' <<<"$out" || fail "${FUNCNAME[0]}" "$out"
  run ".github/workflows/ci.yml" "docs-system: n/a"
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "without reason: rc=$rc"
  run ".github/workflows/ci.yml" "we mention docs-system: n/a inline"
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "not at line start: rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

dependabot_is_exempt() {
  run ".github/workflows/ci.yml" "" "dependabot[bot]"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc $out"
  echo "ok ${FUNCNAME[0]}"
}

ci_runs_it_on_prs_and_gates_on_it() {
  local ci="$root/.github/workflows/ci.yml"
  grep -q 'bash scripts/check-system-docs.sh' <<<"$(yq -r '.jobs["system-docs"].steps[].run // ""' "$ci")" \
    || fail "${FUNCNAME[0]}" "system-docs job does not run the script"
  [ "$(yq -r '.jobs["system-docs"].if' "$ci")" = "github.event_name == 'pull_request'" ] || fail "${FUNCNAME[0]}" "if: $(yq -r '.jobs["system-docs"].if' "$ci")"
  yq -r '.jobs["ci-gate"].needs[]' "$ci" | grep -qx system-docs || fail "${FUNCNAME[0]}" "ci-gate does not need system-docs"
  echo "ok ${FUNCNAME[0]}"
}

the_page_has_a_history_entry() {
  local page="$root/docs/system/index.html"
  grep -q 'id="lich-su"' "$page" || fail "${FUNCNAME[0]}" "no history section"
  grep -Eq '<li><time datetime="[0-9]{4}-[0-9]{2}-[0-9]{2}">' "$page" || fail "${FUNCNAME[0]}" "no dated history entry"
  echo "ok ${FUNCNAME[0]}"
}

watched_change_without_docs_fails
watched_change_with_docs_passes
unwatched_change_passes
image_tag_file_is_exempt
opt_out_line_passes_only_with_reason
dependabot_is_exempt
ci_runs_it_on_prs_and_gates_on_it
the_page_has_a_history_entry
