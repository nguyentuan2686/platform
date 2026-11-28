#!/usr/bin/env bash
# Usage: scripts/smoke-astro-chart.sh [BASE_URL]   (curl + grep/sed only)
set -euo pipefail
BASE="${1:-http://localhost:8086}"
fail() { echo "FAIL: $*" >&2; exit 1; }

BODY='{"birth":"1990-07-15T14:30","zone":"Asia/Ho_Chi_Minh","lat":21.0285,"lon":105.8542,"timeKnown":true}'
RESP=$(curl -sS -w '\n%{http_code}' -X POST "$BASE/astro/chart" -H 'Content-Type: application/json' -d "$BODY") || fail "POST /astro/chart unreachable"
STATUS=$(printf '%s' "$RESP" | tail -n1)
[ "$STATUS" = "200" ] || fail "POST /astro/chart expected 200, got '$STATUS'"
JSON=$(printf '%s' "$RESP" | sed '$d' | tr -d '\r\n')

# count PATTERN: occurrences of PATTERN in the response (0 when absent; grep's exit 1 on no
# match must not trip pipefail).
count() { { printf '%s' "$JSON" | grep -o "$1" || true; } | wc -l | tr -d ' '; }

# Only bodies carry "key" (angles and aspects have none), only houses carry "cusp".
KEYS=$(count '"key"')
[ "$KEYS" = "12" ] || fail "expected 12 bodies (\"key\"), got $KEYS"
CUSPS=$(count '"cusp"')
[ "$CUSPS" = "12" ] || fail "expected 12 house cusps, got $CUSPS"
SCHOOL='"school":"tropical;houses=placidus;node=true;aspects=ptolemaic-v1"'
grep -qF "$SCHOOL" <<<"$JSON" || fail "expected $SCHOOL"
echo "OK bodies=$KEYS cusps=$CUSPS school=tropical/placidus"
