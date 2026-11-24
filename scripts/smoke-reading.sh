#!/usr/bin/env bash
# Usage: scripts/smoke-reading.sh [READING_URL]   (curl + grep/sed only)
set -euo pipefail
BASE="${1:-http://localhost:8083}"
fail() { echo "FAIL: $*" >&2; exit 1; }

# post PATH BODY -> sets JSON (single line); fails unless HTTP 200.
post() {
  local resp status
  resp=$(curl -sS -w '\n%{http_code}' -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2") || fail "POST $1 unreachable"
  status=$(printf '%s' "$resp" | tail -n1)
  [ "$status" = "200" ] || fail "POST $1 expected 200, got '$status'"
  JSON=$(printf '%s' "$resp" | sed '$d' | tr -d '\r\n')
}
count() { { printf '%s' "$JSON" | grep -o "$1" || true; } | wc -l | tr -d ' '; }

BIRTH='"birth":"1994-03-12T08:30","zone":"Asia/Ho_Chi_Minh","gender":"male","calendar":"VN"'

post /tuvi/readings/basic "{$BIRTH}"
# Each section has exactly one `"lines"` key (Section is the only schema with it).
SECTIONS=$(count '"lines"')
[ "$SECTIONS" = "17" ] || fail "expected 17 sections, got $SECTIONS"
LAST=$(printf '%s' "$JSON" | grep -o '"id":"[A-Za-z]*"' | tail -n1 | sed 's/.*:"\(.*\)"/\1/')
[ "$LAST" = "disclaimer" ] || fail "expected last section 'disclaimer', got '$LAST'"

post /tuvi/readings/today "{$BIRTH,\"date\":\"2026-11-24\"}"
AREAS=$(count '"level"')
[ "$AREAS" = "4" ] || fail "expected 4 areas, got $AREAS"
EMPTY=$(count '"line":""')
[ "$EMPTY" = "0" ] || fail "an area has an empty line"
LINES=$(count '"line":"[^"]')
[ "$LINES" = "4" ] || fail "expected 4 non-empty area lines, got $LINES"
echo "OK sections=$SECTIONS areas=$AREAS"
