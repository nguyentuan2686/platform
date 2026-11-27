#!/usr/bin/env bash
# Tests for scripts/public-url. Prints "ok <case>" per case; exits non-zero on first failure. Offline (--log-file).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/public-url"
data="$root/scripts/testdata/public-url"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

# A copy of the real domain.yaml with APP_DOMAIN replaced.
domain_file() { # $1 = domain
  sed "s/^\(  APP_DOMAIN:\).*/\1 $1/" "$root/deploy/k8s/overlays/prod/domain/domain.yaml" >"$tmp/domain.yaml"
  grep -q "^  APP_DOMAIN: $1\$" "$tmp/domain.yaml" || fail setup "could not set APP_DOMAIN in the domain.yaml copy"
  echo "$tmp/domain.yaml"
}

quick_log_prints_trycloudflare_url() {
  local got
  got=$(PUBLIC_URL_DOMAIN_FILE="$(domain_file app.example.com)" bash "$script" --log-file "$data/quick.log") \
    || fail "${FUNCNAME[0]}" "expected exit 0"
  [ "$got" = "https://brave-river-42-delta.trycloudflare.com" ] || fail "${FUNCNAME[0]}" "got [$got]"
  echo "ok ${FUNCNAME[0]}"
}

none_log_without_domain_exits_2() {
  local rc=0
  # the committed domain.yaml (app.example.invalid until go-live) and an invalid copy both mean "no domain"
  PUBLIC_URL_DOMAIN_FILE="$(domain_file app.example.invalid)" bash "$script" --log-file "$data/none.log" >"$tmp/out" 2>"$tmp/err" || rc=$?
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "expected exit 2, got $rc: $(cat "$tmp/out" "$tmp/err")"
  grep -q 'no public URL yet' "$tmp/err" || fail "${FUNCNAME[0]}" "missing message: $(cat "$tmp/err")"
  [ ! -s "$tmp/out" ] || fail "${FUNCNAME[0]}" "printed a URL: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

named_log_with_domain_prints_domain() {
  local got
  got=$(PUBLIC_URL_DOMAIN_FILE="$(domain_file app.example.com)" bash "$script" --log-file "$data/named.log") \
    || fail "${FUNCNAME[0]}" "expected exit 0"
  [ "$got" = "https://app.example.com" ] || fail "${FUNCNAME[0]}" "got [$got]"
  echo "ok ${FUNCNAME[0]}"
}

quick_log_prints_trycloudflare_url
none_log_without_domain_exits_2
named_log_with_domain_prints_domain
