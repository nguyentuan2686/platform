#!/usr/bin/env bash
# Tests for scripts/ops-kubeconfig.sh with a fake kubectl. Prints "ok <case>" per case; exits non-zero on first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/ops-kubeconfig.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

# Fake kubectl: FAKE_SA=missing makes the ServiceAccount lookup fail. Records its arguments.
cat >"$tmp/kubectl" <<'SH'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_LOG"
case "$*" in
  *"get serviceaccount ops-reader"*) [ "${FAKE_SA:-}" != missing ] ;;
  *"create token ops-reader"*) echo "local-test-token-abc" ;;
  *"config view"*) echo "TE9DQUwtVEVTVC1DQQ==" ;;
  *) exit 9 ;;
esac
SH
chmod +x "$tmp/kubectl"
export KUBECTL="$tmp/kubectl" FAKE_LOG="$tmp/log"

writes_private_kubeconfig() {
  local out="$tmp/a.kubeconfig" got
  got=$(bash "$script" --out "$out" --duration 24h) || fail "${FUNCNAME[0]}" "expected exit 0"
  grep -q 'token: "local-test-token-abc"' "$out" || fail "${FUNCNAME[0]}" "token missing"
  grep -q 'server: "https://platform-prod:6443", certificate-authority-data: "TE9DQUwtVEVTVC1DQQ=="' "$out" \
    || fail "${FUNCNAME[0]}" "cluster wrong: $(cat "$out")"
  grep -q 'current-context: prod-ops-reader' "$out" || fail "${FUNCNAME[0]}" "context missing"
  grep -q -- '--duration=24h' "$FAKE_LOG" || fail "${FUNCNAME[0]}" "duration not passed: $(cat "$FAKE_LOG")"
  [[ "$got" != *local-test-token* ]] || fail "${FUNCNAME[0]}" "printed the token"
  if [ "$(uname -s)" = Linux ]; then
    [ "$(stat -c %a "$out")" = 600 ] || fail "${FUNCNAME[0]}" "mode $(stat -c %a "$out")"
  fi
  echo "ok ${FUNCNAME[0]}"
}

missing_serviceaccount_writes_nothing() {
  local out="$tmp/b.kubeconfig" rc=0
  FAKE_SA=missing bash "$script" --out "$out" >"$tmp/o" 2>"$tmp/e" || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc"
  grep -q 'ops-reader not found' "$tmp/e" || fail "${FUNCNAME[0]}" "message: $(cat "$tmp/e")"
  [ ! -e "$out" ] || fail "${FUNCNAME[0]}" "wrote $out"
  echo "ok ${FUNCNAME[0]}"
}

bad_arguments_are_refused() {
  local rc=0
  bash "$script" --out "$tmp/c" --duration forever >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "bad duration: expected exit 1, got $rc"
  rc=0
  bash "$script" --out "$tmp/c" --server 'http://x:1' >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "http server: expected exit 1, got $rc"
  [ ! -e "$tmp/c" ] || fail "${FUNCNAME[0]}" "wrote a file"
  echo "ok ${FUNCNAME[0]}"
}

writes_private_kubeconfig
missing_serviceaccount_writes_nothing
bad_arguments_are_refused
