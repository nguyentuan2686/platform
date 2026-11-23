#!/usr/bin/env bash
# Usage: scripts/smoke-order.sh [BASE_URL]   (curl + grep/sed only)
set -u
BASE="${1:-http://localhost:8080}"
fail() { echo "FAIL: $*" >&2; exit 1; }

BODY='{"customerId":"smoke-customer","items":[{"sku":"SKU-1","quantity":2,"unitPrice":10.50},{"sku":"SKU-2","quantity":1,"unitPrice":4.00}]}'
RESP=$(curl -sS -D - -o - -X POST "$BASE/orders" -H 'Content-Type: application/json' -d "$BODY") || fail "POST /orders unreachable"
STATUS=$(printf '%s' "$RESP" | head -n1 | tr -d '\r' | awk '{print $2}')
[ "$STATUS" = "201" ] || fail "POST /orders expected 201, got '$STATUS'"
LOCATION=$(printf '%s' "$RESP" | tr -d '\r' | grep -i '^location:' | head -n1 | sed 's/^[^:]*: *//')
[ -n "$LOCATION" ] || fail "no Location header"
TOTAL1=$(printf '%s' "$RESP" | tr -d '\r\n' | sed -n 's/.*"total"[^0-9-]*\([0-9.]*\).*/\1/p')
[ -n "$TOTAL1" ] || fail "no total in POST response"

case "$LOCATION" in http*) URL="$LOCATION" ;; *) URL="$BASE$LOCATION" ;; esac
GRESP=$(curl -sS -w '\n%{http_code}' "$URL") || fail "GET $URL unreachable"
GSTATUS=$(printf '%s' "$GRESP" | tail -n1)
[ "$GSTATUS" = "200" ] || fail "GET $URL expected 200, got $GSTATUS"
TOTAL2=$(printf '%s' "$GRESP" | tr -d '\r\n' | sed -n 's/.*"total"[^0-9-]*\([0-9.]*\).*/\1/p')
[ "$TOTAL1" = "$TOTAL2" ] || fail "total mismatch: POST=$TOTAL1 GET=$TOTAL2"
echo "OK $LOCATION total=$TOTAL1"
