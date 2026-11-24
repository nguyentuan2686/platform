#!/usr/bin/env bash
# Usage: scripts/smoke-chart.sh [BASE_URL]   (curl + grep/sed only)
set -u
BASE="${1:-http://localhost:8082}"
fail() { echo "FAIL: $*" >&2; exit 1; }

BODY='{"birth":"1994-03-12T08:30","zone":"Asia/Ho_Chi_Minh","gender":"male","calendar":"VN"}'
RESP=$(curl -sS -w '\n%{http_code}' -X POST "$BASE/tuvi/chart" -H 'Content-Type: application/json' -d "$BODY") || fail "POST /tuvi/chart unreachable"
STATUS=$(printf '%s' "$RESP" | tail -n1)
[ "$STATUS" = "200" ] || fail "POST /tuvi/chart expected 200, got '$STATUS'"
JSON=$(printf '%s' "$RESP" | sed '$d' | tr -d '\r\n')

# Palaces: each palace object carries exactly one "isBody" key.
PALACES=$(printf '%s' "$JSON" | grep -o '"isBody"' | wc -l | tr -d ' ')
[ "$PALACES" = "12" ] || fail "expected 12 palaces, got $PALACES"
MONTH=$(printf '%s' "$JSON" | sed -n 's/.*"lunar"[^}]*"month"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
[ "$MONTH" = "2" ] || fail "expected lunar.month 2, got '$MONTH'"
echo "OK palaces=$PALACES lunar.month=$MONTH"
