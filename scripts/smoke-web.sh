#!/usr/bin/env bash
# Usage: scripts/smoke-web.sh [BASE_URL]   (curl only). Local web is on http://localhost:3001.
set -euo pipefail
BASE="${1:-http://localhost:3001}"
fail() { echo "FAIL: $*" >&2; exit 1; }
code() { curl -sS -o /dev/null -w '%{http_code}' "$BASE$1" || fail "GET $1 unreachable"; }

STATUS=$(code /health/ready)
[ "$STATUS" = "200" ] || fail "GET /health/ready expected 200, got '$STATUS'"
PAGE=$(curl -sS "$BASE/vi") || fail "GET /vi unreachable"
printf '%s' "$PAGE" | grep -q 'Lão Thiên Cơ' || fail "GET /vi does not contain 'Lão Thiên Cơ'"
for path in /vi/kien-thuc /robots.txt; do
  STATUS=$(code "$path")
  [ "$STATUS" = "200" ] || fail "GET $path expected 200, got '$STATUS'"
done
echo OK
