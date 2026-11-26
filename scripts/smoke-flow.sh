#!/usr/bin/env bash
# Usage: scripts/smoke-flow.sh [BASE_ORDER] [BASE_NOTIFY]   (curl + grep/sed only)
# Places an order via order-svc, then waits (<= 60s) for notify-svc to list a notification for it. 60s, not less: on a
# cold start the consumer may only now be joining its group and picking up a just-created topic (metadata refresh
# 10s, plus the group rebalance), so the first order can take tens of seconds; later ones take well under a second.
set -u
BASE_ORDER="${1:-http://localhost:8080}"
BASE_NOTIFY="${2:-http://localhost:8081}"
fail() { echo "FAIL: $*" >&2; exit 1; }

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=$("$DIR/smoke-order.sh" "$BASE_ORDER") || fail "smoke-order failed: $OUT"
echo "$OUT"
# "OK /orders/<uuid> total=..." -> uuid is the last path segment of the Location.
ORDER_ID=$(sed -n 's#^OK [^ ]*/\([0-9a-fA-F-]\{36\}\) .*#\1#p' <<<"$OUT")
ORDER_ID="${ORDER_ID%%$'\n'*}"
[ -n "$ORDER_ID" ] || fail "could not extract order id from: $OUT"

DEADLINE=$((SECONDS + 60))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  # Response: JSON array of {"id","orderId","customerId","channel","message","createdAt"}.
  RESP=$(curl -sS --max-time 3 "$BASE_NOTIFY/notifications?limit=100" 2>/dev/null || true)
  if grep -q "\"orderId\" *: *\"$ORDER_ID\"" <<<"$(tr -d '\r\n' <<<"$RESP")"; then
    echo "OK order=$ORDER_ID notified"
    exit 0
  fi
  sleep 1
done
fail "no notification for order $ORDER_ID within 60s"
