#!/usr/bin/env bash
# Tests for the optional SMOKE_HOST_HEADER of scripts/smoke-web.sh, offline: a stub `curl` first in PATH records its argv
# (one call per line, NUL-free, arguments separated by a unit separator) and prints canned bodies.
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

mkdir -p "$tmp/bin"
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
# Records argv; `-w <fmt>` gets 200, any other call gets a page that passes the /vi content check.
( IFS=$'\x1f'; printf '%s\n' "$*" ) >>"$FAKE_CURL_LOG"
for a in "$@"; do [ "$a" = -w ] && { printf 200; exit 0; }; done
printf '<html>Lão Thiên Cơ</html>'
EOF
chmod +x "$tmp/bin/curl"

run() { # VAR=value... ; sets rc, writes $tmp/out and $tmp/calls
  rc=0
  : >"$tmp/calls"
  env "$@" PATH="$tmp/bin:$PATH" FAKE_CURL_LOG="$tmp/calls" bash "$root/scripts/smoke-web.sh" http://127.0.0.1:18080 \
    >"$tmp/out" 2>&1 || rc=$?
}

host_header_sent_when_set() {
  run SMOKE_HOST_HEADER=x
  [ "$rc" -eq 0 ] && grep -qx OK "$tmp/out" || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(wc -l <"$tmp/calls")" -ge 4 ] || fail "${FUNCNAME[0]}" "expected >= 4 curl calls, got $(wc -l <"$tmp/calls")"
  while IFS= read -r line; do
    [[ $'\x1f'"$line"$'\x1f' == *$'\x1f-H\x1fHost: x\x1f'* ]] || fail "${FUNCNAME[0]}" "call without -H 'Host: x': ${line//$'\x1f'/ }"
  done <"$tmp/calls"
  echo "ok ${FUNCNAME[0]}"
}

no_host_header_by_default() {
  run -u SMOKE_HOST_HEADER
  [ "$rc" -eq 0 ] && grep -qx OK "$tmp/out" || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(wc -l <"$tmp/calls")" -ge 4 ] || fail "${FUNCNAME[0]}" "expected >= 4 curl calls"
  if grep -q 'Host:' "$tmp/calls"; then fail "${FUNCNAME[0]}" "a Host header was sent: $(tr '\037' ' ' <"$tmp/calls")"; fi
  run SMOKE_HOST_HEADER=
  if grep -q 'Host:' "$tmp/calls"; then fail "${FUNCNAME[0]}" "empty SMOKE_HOST_HEADER sent a Host header"; fi
  echo "ok ${FUNCNAME[0]}"
}

host_header_sent_when_set
no_host_header_by_default
