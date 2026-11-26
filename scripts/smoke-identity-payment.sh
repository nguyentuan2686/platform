#!/usr/bin/env bash
# Usage: scripts/smoke-identity-payment.sh [IDENTITY_URL] [PAYMENT_URL] [MAILPIT_URL] [WEB_URL]   (curl + sed only)
# Needs identity-svc, payment-svc, Mailpit, Postgres and Redpanda (local compose, --profile app).
# Flow: guest -> magic link (mail read from Mailpit) -> login (1 signup credit) -> CREDIT_5 order
#       -> simulated SePay transfer -> credits 6 -> duplicate delivery -> credits still 6.
# With WEB_URL (web with DEV_PAYMENT_SIMULATOR=1, e.g. k8s-e2e): a second CREDIT_5 order is paid through web's dev
# simulator (POST /api/dev/sepay as the logged-in viewer) -> credits 11. That proves web signs with the same
# SEPAY_WEBHOOK_SECRET as payment-svc (a mismatch is a 401 upstream, answered 503).
set -euo pipefail
IDENTITY="${1:-http://localhost:8084}"
PAYMENT="${2:-http://localhost:8085}"
MAILPIT="${3:-http://localhost:8025}"
WEB="${4:-}"
TOKEN="${INTERNAL_API_TOKEN:-local-test-internal-token}"
HERE="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

# call METHOD URL [BODY [BEARER]] -> sets STATUS and JSON (single line)
call() {
  local args=(-sS -w '\n%{http_code}' -X "$1" "$2" -H "X-Internal-Token: $TOKEN")
  [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' -d "$3")
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  local resp
  resp=$(curl "${args[@]}") || fail "$1 $2 unreachable"
  STATUS=$(printf '%s' "$resp" | tail -n1)
  JSON=$(printf '%s' "$resp" | sed '$d' | tr -d '\r\n')
}
field() { printf '%s' "$JSON" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
credits() {
  call GET "$IDENTITY/me/entitlements" "" "$SESSION"
  [ "$STATUS" = "200" ] || fail "GET /me/entitlements expected 200, got $STATUS"
  printf '%s' "$JSON" | sed -n 's/.*"credits":\([0-9]*\).*/\1/p'
}

EMAIL="smoke+$(date +%s)@example.test"

call POST "$IDENTITY/guests"
[ "$STATUS" = "201" ] || fail "POST /guests expected 201, got $STATUS"
GUEST=$(field session_token)
[ -n "$GUEST" ] || fail "no guest session_token"

# Start from an empty mailbox so a stale message can never be picked.
curl -sS -X DELETE "$MAILPIT/api/v1/messages" >/dev/null || fail "Mailpit unreachable"
call POST "$IDENTITY/auth/magic-links" "{\"email\":\"$EMAIL\",\"lang\":\"vi\"}" "$GUEST"
[ "$STATUS" = "202" ] || fail "POST /auth/magic-links expected 202, got $STATUS"

# Newest message in Mailpit (the API lists newest first); wait up to 10 s for delivery.
MSG_ID=""
for _ in $(seq 1 20); do
  LIST=$(curl -sS "$MAILPIT/api/v1/messages?limit=1" | tr -d '\r\n') || fail "Mailpit unreachable"
  MSG_ID=$(printf '%s' "$LIST" | sed -n 's/.*"messages":\[{"ID":"\([^"]*\)".*/\1/p')
  [ -n "$MSG_ID" ] && break
  sleep 0.5
done
[ -n "$MSG_ID" ] || fail "no mail arrived in Mailpit"
MAIL=$(curl -sS "$MAILPIT/api/v1/message/$MSG_ID" | tr -d '\r\n')
grep -qF "$EMAIL" <<<"$MAIL" || fail "newest mail is not addressed to this run"
LINK_TOKEN=$(printf '%s' "$MAIL" | sed -n 's/.*xac-nhan?token=\([A-Za-z0-9_-]*\).*/\1/p')
[ -n "$LINK_TOKEN" ] || fail "no token in the newest mail"

call POST "$IDENTITY/auth/magic-links/verify" "{\"token\":\"$LINK_TOKEN\"}" "$GUEST"
[ "$STATUS" = "200" ] || fail "verify expected 200, got $STATUS"
SESSION=$(field session_token)
ACCOUNT=$(field account_id)
[ -n "$SESSION" ] && [ -n "$ACCOUNT" ] || fail "verify returned no session/account"

C=$(credits)
[ "$C" = "1" ] || fail "expected 1 signup credit, got '$C'"

call POST "$PAYMENT/orders" "{\"account_id\":\"$ACCOUNT\",\"site\":\"tuvi\",\"product_code\":\"CREDIT_5\",\"currency\":\"VND\",\"provider\":\"SEPAY\",\"adult_confirmed\":true}"
[ "$STATUS" = "201" ] || fail "POST /orders expected 201, got $STATUS: $JSON"
CODE=$(field code)
[ -n "$CODE" ] || fail "order has no code"

TX="$(date +%s)$$"
OUT=$("$HERE/sepay-simulate.sh" "$PAYMENT" "$CODE" 119000 "$TX") || fail "sepay-simulate failed: $OUT"
grep -q 'HTTP 200' <<<"$OUT" || fail "webhook not accepted: $OUT"

C=""
for _ in $(seq 1 30); do
  C=$(credits)
  [ "$C" = "6" ] && break
  sleep 0.5
done
[ "$C" = "6" ] || fail "expected 6 credits within 15 s, got '$C'"

# The same bank transaction delivered again must change nothing.
OUT=$("$HERE/sepay-simulate.sh" "$PAYMENT" "$CODE" 119000 "$TX") || fail "duplicate webhook failed: $OUT"
grep -q 'HTTP 200' <<<"$OUT" || fail "duplicate webhook not accepted: $OUT"
sleep 3
C=$(credits)
[ "$C" = "6" ] || fail "duplicate delivery changed credits to '$C'"


if [ -n "$WEB" ]; then
  call POST "$PAYMENT/orders" "{\"account_id\":\"$ACCOUNT\",\"site\":\"tuvi\",\"product_code\":\"CREDIT_5\",\"currency\":\"VND\",\"provider\":\"SEPAY\",\"adult_confirmed\":true}"
  [ "$STATUS" = "201" ] || fail "POST /orders (simulator) expected 201, got $STATUS: $JSON"
  SIM_ORDER=$(field order_id)
  [ -n "$SIM_ORDER" ] || fail "simulator order has no order_id"
  # The web session cookie holds the identity session token (COOKIE_SECURE=0: tv_sid); Origin must be WEB_ORIGIN.
  SIM_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$WEB/api/dev/sepay" \
    -H "Origin: $WEB" -H "Cookie: tv_sid=$SESSION" -H 'Content-Type: application/json' \
    -d "{\"order_id\":\"$SIM_ORDER\"}") || fail "web $WEB unreachable"
  [ "$SIM_STATUS" = "204" ] || fail "POST $WEB/api/dev/sepay expected 204, got $SIM_STATUS (503 = web and payment-svc disagree on SEPAY_WEBHOOK_SECRET)"
  for _ in $(seq 1 30); do
    C=$(credits)
    [ "$C" = "11" ] && break
    sleep 0.5
  done
  [ "$C" = "11" ] || fail "expected 11 credits within 15 s after the web simulator transfer, got '$C'"
  echo "OK web-dev-simulator order=$SIM_ORDER credits=$C"
fi

echo "OK order=$CODE credits=$C"
