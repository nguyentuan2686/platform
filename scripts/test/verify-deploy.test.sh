#!/usr/bin/env bash
# Tests for scripts/verify-deploy.sh, offline: a fake kubectl serves Application JSON from scripts/testdata/verify-deploy
# (optionally a per-call sequence) and fakes `port-forward`; a stub curl answers the smoke checks of the real
# scripts/smoke-web.sh and records its argv. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/verify-deploy.sh"
data="$root/scripts/testdata/verify-deploy"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
rev=1111111111111111111111111111111111111111
domain="$(awk '$1 == "APP_DOMAIN:" {print $2}' "$root/deploy/k8s/overlays/prod/domain/domain.yaml")"
us=$'\x1f'
mark="vd-$$-$RANDOM" # unique argv marker of this run's fake port-forward (pgrep must not match other processes)

fail() { echo "FAIL $1: $2" >&2; exit 1; }

mkdir -p "$tmp/bin" "$tmp/apps"
# kubectl -n argocd get application <name> -o json -> $tmp/apps/<name>.json, or the next line of <name>.seq (fixture
# names; the last one repeats), else NotFound. kubectl ... port-forward ...: argv to .pf-args; .pf-fail -> exit 1.
cat >"$tmp/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
for a in "$@"; do
  if [ "$a" = port-forward ]; then
    echo "$*" >"$FAKE_APPS/.pf-args"
    [ -f "$FAKE_APPS/.pf-fail" ] && { echo 'error: services "traefik" not found' >&2; exit 1; }
    exec -a "fake-port-forward-$FAKE_MARK" sleep 600
  fi
done
[ -f "$FAKE_APPS/.down" ] && { echo "Unable to connect to the server: dial tcp 100.64.0.1:6443: i/o timeout" >&2; exit 1; }
name=""; prev=""
for a in "$@"; do [ "$prev" = application ] && name="$a"; prev="$a"; done
f="$FAKE_APPS/$name.json"
if [ -n "$name" ] && [ -f "$FAKE_APPS/$name.seq" ]; then
  n=$(( $(cat "$FAKE_APPS/$name.n" 2>/dev/null || echo 0) + 1 )); echo "$n" >"$FAKE_APPS/$name.n"
  fx="$(sed -n "${n}p" "$FAKE_APPS/$name.seq")"; [ -n "$fx" ] || fx="$(tail -n 1 "$FAKE_APPS/$name.seq")"
  f="$FAKE_DATA/$fx.json"
fi
[ -f "$FAKE_APPS/.warn" ] && echo 'time="x" level=warning msg="open /etc/rancher/k3s/config.yaml: permission denied"' >&2
[ -n "$name" ] && [ -f "$f" ] || { echo "Error from server (NotFound): applications.argoproj.io \"$name\" not found" >&2; exit 1; }
cat "$f"
EOF
# curl: argv (unit-separated) to .curl-log; `-w` prints $FAKE_APPS/.code (default 200); else a page for /vi.
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
( IFS=$'\x1f'; printf '%s\n' "$*" ) >>"$FAKE_APPS/.curl-log"
[ -f "$FAKE_APPS/.pf-fail" ] && { printf 000; exit 7; }
for a in "$@"; do [ "$a" = -w ] && { cat "$FAKE_APPS/.code" 2>/dev/null || printf 200; exit 0; }; done
printf '<html>Lão Thiên Cơ</html>'
EOF
chmod +x "$tmp/bin/kubectl" "$tmp/bin/curl"

reset() { rm -f "$tmp/apps"/* "$tmp/apps"/.[!.]*; }
use() { # use <app> <fixture>
  cp "$data/$2.json" "$tmp/apps/$1.json"
}

run() { # args...; sets rc, writes $tmp/out
  rc=0
  PATH="$tmp/bin:$PATH" FAKE_MARK="$mark" FAKE_APPS="$tmp/apps" FAKE_DATA="$data" VERIFY_DEPLOY_INTERVAL_S=0 VERIFY_DEPLOY_SMOKE_DEADLINE_S=2 \
    bash "$script" "$@" >"$tmp/out" 2>&1 || rc=$?
}

synced_and_healthy_passes() {
  reset; use root healthy; use prod-platform healthy
  run --app root,prod-platform --revision "$rev" --deadline-s 5 --no-smoke
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK argo-root' "$tmp/out" && grep -qx 'OK argo-prod-platform' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

wrong_revision_fails_with_details() {
  reset; use prod-platform healthy
  run --app prod-platform --revision 0000000000000000000000000000000000000000 --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit: $(cat "$tmp/out")"
  grep -q "^FAIL argo-prod-platform: sync=Synced health=Healthy revision=$rev conditions=" "$tmp/out" \
    || fail "${FUNCNAME[0]}" "no FAIL line with details: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

progressing_times_out() {
  reset; use prod-platform progressing
  run --app prod-platform --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-platform: sync=Synced health=Progressing' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

missing_app_fails() {
  reset
  run --app prod-sealed --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-sealed: ' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

progressing_names_the_unhealthy_resources() {
  reset; use prod-apps progressing-ingress
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out")"
  grep -q '^  unhealthy Ingress/apps/web: Progressing' "$tmp/out" || fail "${FUNCNAME[0]}" "no unhealthy line: $(cat "$tmp/out")"
  if grep -q 'unhealthy Deployment\|unhealthy Service' "$tmp/out"; then fail "${FUNCNAME[0]}" "listed a healthy resource: $(cat "$tmp/out")"; fi
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_passes() {
  reset; use root healthy; use prod-apps outofsync; use prod-tunnel outofsync
  run --app root --revision "$rev" --deadline-s 5 --no-smoke --expect-outofsync prod-apps,prod-tunnel
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK argo-prod-apps (OutOfSync, renders)' "$tmp/out" && grep -qx 'OK argo-prod-tunnel (OutOfSync, renders)' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_rejects_comparison_error() {
  reset; use prod-apps comparison-error
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke --expect-outofsync prod-apps
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  grep -q '^FAIL argo-prod-apps: .*ComparisonError' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

expect_outofsync_rejects_synced() {
  reset; use prod-apps healthy
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke --expect-outofsync prod-apps
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit (CI mode must not sync prod-apps)"
  echo "ok ${FUNCNAME[0]}"
}

stderr_warning_does_not_break_parsing() {
  # k3s's multi-call kubectl warns on stderr (e.g. it cannot read /etc/rancher/k3s/config.yaml); JSON is on stdout.
  reset; use root healthy
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

all_apps_must_hold_at_once() {
  # Review M6: an app that was at the target once and then regresses does not pass; all must hold in the same poll.
  # Poll 1: prod-platform at the target, root not yet. Poll 2 on: root at the target, prod-platform regressed.
  reset
  printf 'healthy\nprogressing\n' >"$tmp/apps/prod-platform.seq"
  printf 'progressing\nhealthy\n' >"$tmp/apps/root.seq"
  run --app prod-platform,root --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^FAIL argo-prod-platform: sync=Synced health=Progressing' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

descendant_revision_needs_opt_in() {
  # Argo tracks main: a commit merged during the wait moves every app past the bump. With --allow-descendant that
  # counts; an unrelated revision never does.
  local g="$tmp/git" base desc other
  rm -rf "$g"; git init -q -b main "$g"
  gc() { git -C "$g" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m "$1"; }
  gc base; base="$(git -C "$g" rev-parse HEAD)"; gc desc; desc="$(git -C "$g" rev-parse HEAD)"
  git -C "$g" checkout -q --orphan other; gc other; other="$(git -C "$g" rev-parse HEAD)"; git -C "$g" checkout -q main
  reset
  jq --arg r "$desc" '.status.sync.revision = $r' "$data/healthy.json" >"$tmp/apps/prod-apps.json"
  VERIFY_DEPLOY_GIT_DIR="$g" run --app prod-apps --revision "$base" --deadline-s 1 --no-smoke
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "descendant accepted without --allow-descendant: rc=$rc"
  VERIFY_DEPLOY_GIT_DIR="$g" run --app prod-apps --revision "$base" --deadline-s 5 --no-smoke --allow-descendant
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx "OK argo-prod-apps (at ${desc:0:7}, descendant of ${base:0:7})" "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  jq --arg r "$other" '.status.sync.revision = $r' "$data/healthy.json" >"$tmp/apps/prod-apps.json"
  VERIFY_DEPLOY_GIT_DIR="$g" run --app prod-apps --revision "$base" --deadline-s 1 --no-smoke --allow-descendant
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "unrelated revision accepted: rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

smoke_runs_through_traefik_with_app_domain() {
  reset; use prod-apps healthy
  run --app prod-apps --revision "$rev" --deadline-s 5
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'OK smoke-web' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  grep -q -- '-n kube-system port-forward svc/traefik 18080:80' "$tmp/apps/.pf-args" \
    || fail "${FUNCNAME[0]}" "port-forward args: $(cat "$tmp/apps/.pf-args" 2>/dev/null)"
  grep -q "http://127.0.0.1:18080/vi" "$tmp/apps/.curl-log" || fail "${FUNCNAME[0]}" "smoke did not hit the forwarded port"
  # Every smoke-web call carries Host: <APP_DOMAIN> (the Ingress host).
  local n=0 line
  while IFS= read -r line; do
    case "$line" in *127.0.0.1:18080/health/ready*|*127.0.0.1:18080/vi*|*127.0.0.1:18080/robots.txt*) ;; *) continue ;; esac
    n=$((n + 1))
    [[ "$us$line$us" == *"$us-H${us}Host: $domain$us"* ]] || fail "${FUNCNAME[0]}" "no Host header: ${line//$us/ }"
  done <"$tmp/apps/.curl-log"
  [ "$n" -ge 4 ] || fail "${FUNCNAME[0]}" "expected >= 4 smoke calls, got $n"
  echo "ok ${FUNCNAME[0]}"
}

smoke_failure_exits_1() {
  reset; use prod-apps healthy; printf 503 >"$tmp/apps/.code"
  run --app prod-apps --revision "$rev" --deadline-s 5
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^FAIL smoke-web: ' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

port_forward_failure_exits_4() {
  # The cluster could not be checked: unverified, not a bad deploy (exit 4, never a rollback).
  reset; use prod-apps healthy; touch "$tmp/apps/.pf-fail"
  run --app prod-apps --revision "$rev" --deadline-s 5
  [ "$rc" -eq 4 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^FAIL port-forward: ' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unreachable_cluster_exits_4() {
  # Tailnet/API down: no app readable at all -> exit 4 (unverified), not exit 1 (bad deploy, which reverts).
  reset; use prod-apps healthy; use prod-platform healthy; touch "$tmp/apps/.down"
  run --app prod-platform,prod-apps --revision "$rev" --deadline-s 1
  [ "$rc" -eq 4 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^UNREACHABLE: ' "$tmp/out" && grep -q '^FAIL argo-prod-apps: .*Unable to connect' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

not_found_app_is_a_failed_deploy() {
  # The API answered (NotFound / Progressing): the cluster was checked, so it is exit 1, not 4.
  reset; use prod-platform progressing
  run --app prod-platform,prod-apps --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  reset
  run --app prod-apps --revision "$rev" --deadline-s 1 --no-smoke
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "only NotFound: rc=$rc: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

no_port_forward_left_running() {
  reset; use prod-apps healthy
  run --app prod-apps --revision "$rev" --deadline-s 5
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  local deadline=$((SECONDS + 5))
  while pgrep -f "fake-port-forward-$mark" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "${FUNCNAME[0]}" "port-forward still running after exit"
    sleep 0.2
  done
  echo "ok ${FUNCNAME[0]}"
}

synced_and_healthy_passes
wrong_revision_fails_with_details
progressing_times_out
progressing_names_the_unhealthy_resources
missing_app_fails
expect_outofsync_passes
expect_outofsync_rejects_comparison_error
expect_outofsync_rejects_synced
stderr_warning_does_not_break_parsing
bad_arguments_exit_2
all_apps_must_hold_at_once
descendant_revision_needs_opt_in
smoke_runs_through_traefik_with_app_domain
smoke_failure_exits_1
port_forward_failure_exits_4
unreachable_cluster_exits_4
not_found_app_is_a_failed_deploy
no_port_forward_left_running
