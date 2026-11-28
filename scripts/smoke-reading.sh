#!/usr/bin/env bash
# Usage: scripts/smoke-reading.sh [READING_URL] [IDENTITY_URL]   (curl + grep/sed only)
# Basic + today readings, then one SIM number check (xem số) as a fresh guest of identity-svc.
set -euo pipefail
BASE="${1:-http://localhost:8083}"
IDENTITY="${2:-http://localhost:8084}"
TOKEN="${INTERNAL_API_TOKEN:-local-test-internal-token}"
fail() { echo "FAIL: $*" >&2; exit 1; }

# post URL BODY [EXPECTED_STATUS [BEARER]] -> sets JSON (single line); fails on another status.
post() {
  local resp status args=(-sS -w '\n%{http_code}' -X POST "$1" -H "X-Internal-Token: $TOKEN")
  [ -n "$2" ] && args+=(-H 'Content-Type: application/json' -d "$2")
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  resp=$(curl "${args[@]}") || fail "POST $1 unreachable"
  status=$(printf '%s' "$resp" | tail -n1)
  [ "$status" = "${3:-200}" ] || fail "POST $1 expected ${3:-200}, got '$status'"
  JSON=$(printf '%s' "$resp" | sed '$d' | tr -d '\r\n')
}
count() { { printf '%s' "$JSON" | grep -o "$1" || true; } | wc -l | tr -d ' '; }

BIRTH='"birth":"1994-03-12T08:30","zone":"Asia/Ho_Chi_Minh","gender":"male","calendar":"VN"'

post "$BASE/tuvi/readings/basic" "{$BIRTH}"
# Each section has exactly one `"lines"` key (Section is the only schema with it).
SECTIONS=$(count '"lines"')
[ "$SECTIONS" = "17" ] || fail "expected 17 sections, got $SECTIONS"
LAST=$(printf '%s' "$JSON" | grep -o '"id":"[A-Za-z]*"' | tail -n1 | sed 's/.*:"\(.*\)"/\1/')
[ "$LAST" = "disclaimer" ] || fail "expected last section 'disclaimer', got '$LAST'"

post "$BASE/tuvi/readings/today" "{$BIRTH,\"date\":\"2026-11-24\"}"
AREAS=$(count '"level"')
[ "$AREAS" = "4" ] || fail "expected 4 areas, got $AREAS"
EMPTY=$(count '"line":""')
[ "$EMPTY" = "0" ] || fail "an area has an empty line"
LINES=$(count '"line":"[^"]')
[ "$LINES" = "4" ] || fail "expected 4 non-empty area lines, got $LINES"

# Number check: a fresh guest has its own 2-a-day quota; a test SIM must get a numeric score and 6 criteria.
post "$IDENTITY/guests" "" 201
GUEST=$(printf '%s' "$JSON" | sed -n 's/.*"session_token":"\([^"]*\)".*/\1/p')
[ -n "$GUEST" ] || fail "no guest session_token"
post "$BASE/tuvi/number-check" '{"kind":"sim","number":"0912345678","birthYear":1990,"beforeTet":false,"gender":"male"}' 200 "$GUEST"
SCORE=$(printf '%s' "$JSON" | sed -n 's/.*"score":\([0-9][0-9.]*\).*/\1/p')
[ -n "$SCORE" ] || fail "number check: no numeric score"
CRITERIA=$(count '"max":')
[ "$CRITERIA" = "6" ] || fail "number check: expected 6 criteria, got $CRITERIA"
echo "OK sections=$SECTIONS areas=$AREAS number_score=$SCORE"
