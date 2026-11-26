#!/usr/bin/env bash
# Tests for the Argo CD wait part of scripts/verify-deploy.sh, offline: a fake kubectl serves Application JSON from
# scripts/testdata/verify-deploy. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/verify-deploy.sh"
data="$root/scripts/testdata/verify-deploy"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
rev=1111111111111111111111111111111111111111

fail() { echo "FAIL $1: $2" >&2; exit 1; }

mkdir -p "$tmp/bin" "$tmp/apps"
# kubectl -n argocd get application <name> -o json -> $tmp/apps/<name>.json, else NotFound.
cat >"$tmp/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
name=""; prev=""
for a in "$@"; do [ "$prev" = application ] && name="$a"; prev="$a"; done
f="$FAKE_APPS/$name.json"
[ -f "$FAKE_APPS/.warn" ] && echo "time=\"x\" level=warning msg=\"open /etc/rancher/k3s/config.yaml: permission denied\"" >&2
[ -n "$name" ] && [ -f "$f" ] || { echo "Error from server (NotFound): applications.argoproj.io \"$name\" not found" >&2; exit 1; }
cat "$f"
EOF
chmod +x "$tmp/bin/kubectl"

use() { # use <app> <fixture>
  cp "$data/$2.json" "$tmp/apps/$1.json"
}

run() { # args...; sets rc, writes $tmp/out
  rc=0
  PATH="$tmp/bin:$PATH" FAKE_APPS="$tmp/apps" VERIFY_DEPLOY_INTERVAL_S=0 bash "$script" "$@" >"$tmp/out" 2>&1 || rc=$?
}

synced_and_healthy_passes() {
  rm -f "$tmp/apps"/*; use root healthy; use prod-platform healthy
  run --app root,prod-platform --revision "$rev" --deadline-s 5 --no-smoke
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK argo-root' "$tmp/out" && grep -qx 'OK argo-prod-platform' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

wrong_revision_fails_with_details() {
  rm -f "$tmp/apps"/*; use prod-platform healthy
  run --app prod-platform --revision 0000000000000000000000000000000000000000 --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit: $(cat "$tmp/out")"
  grep -q "^FAIL argo-prod-platform: sync=Synced health=Healthy revision=$rev conditions=" "$tmp/out" \
    || fail "${FUNCNAME[0]}" "no FAIL line with details: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

progressing_times_out() {
  rm -f "$tmp/apps"/*; use prod-platform progressing
  run --app prod-platform --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-platform: sync=Synced health=Progressing' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

missing_app_fails() {
  rm -f "$tmp/apps"/*
  run --app prod-sealed --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-sealed: ' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_passes() {
  rm -f "$tmp/apps"/*; use root healthy; use prod-apps outofsync; use prod-tunnel outofsync
  run --app root --revision "$rev" --deadline-s 5 --no-smoke --expect-outofsync prod-apps,prod-tunnel
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK argo-prod-apps (OutOfSync, renders)' "$tmp/out" && grep -qx 'OK argo-prod-tunnel (OutOfSync, renders)' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_rejects_comparison_error() {
  rm -f "$tmp/apps"/*; use prod-apps comparison-error
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke --expect-outofsync prod-apps
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-apps: .*ComparisonError' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_rejects_synced() {
  rm -f "$tmp/apps"/*; use prod-apps healthy
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke --expect-outofsync prod-apps
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit (CI mode must not sync prod-apps)"
  echo "ok ${FUNCNAME[0]}"
}

stderr_warning_does_not_break_parsing() {
  # k3s's multi-call kubectl warns on stderr (e.g. it cannot read /etc/rancher/k3s/config.yaml); JSON is on stdout.
  rm -f "$tmp/apps"/*; use root healthy
  touch "$tmp/apps/.warn"
  run --app root --revision "$rev" --deadline-s 5 --no-smoke
  rm -f "$tmp/apps/.warn"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK argo-root' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

bad_arguments_exit_2() {
  run --revision "$rev"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "missing --app: rc=$rc"
  run --app root --revision main
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "non-sha revision: rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

synced_and_healthy_passes
wrong_revision_fails_with_details
progressing_times_out
missing_app_fails
expect_outofsync_passes
expect_outofsync_rejects_comparison_error
expect_outofsync_rejects_synced
stderr_warning_does_not_break_parsing
bad_arguments_exit_2
