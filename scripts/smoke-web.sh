#!/usr/bin/env bash
# Usage: scripts/smoke-web.sh [BASE_URL]   (curl only). Local web is on http://localhost:3001.
# SMOKE_HOST_HEADER (optional): sent as `Host:` on every request, e.g. the Ingress host when BASE_URL is a
# port-forward to Traefik (scripts/verify-deploy.sh).
set -euo pipefail
BASE="${1:-http://localhost:3001}"
fail() { echo "FAIL: $*" >&2; exit 1; }
hdr=()
[ -z "${SMOKE_HOST_HEADER:-}" ] || hdr=(-H "Host: $SMOKE_HOST_HEADER")
code() { curl -sS --max-time 30 ${hdr[@]+"${hdr[@]}"} -o /dev/null -w '%{http_code}' "$BASE$1" || fail "GET $1 unreachable"; }

STATUS=$(code /health/ready)
[ "$STATUS" = "200" ] || fail "GET /health/ready expected 200, got '$STATUS'"
PAGE=$(curl -sS --max-time 30 ${hdr[@]+"${hdr[@]}"} "$BASE/vi") || fail "GET /vi unreachable"
grep -q 'Lão Thiên Cơ' <<<"$PAGE" || fail "GET /vi does not contain 'Lão Thiên Cơ'"
for path in /vi/kien-thuc /robots.txt; do
  STATUS=$(code "$path")
  [ "$STATUS" = "200" ] || fail "GET $path expected 200, got '$STATUS'"
done
echo OK
