#!/usr/bin/env bash
# Simulates a SePay "money in" webhook against a local payment-svc.
# Usage: scripts/sepay-simulate.sh <PAYMENT_URL> <ORDER_CODE> <AMOUNT> [TX_ID]
#   e.g. scripts/sepay-simulate.sh http://localhost:8085 TV8K2QAB 119000
# Sending the same TX_ID twice shows that a duplicate delivery changes nothing.
# Auth: apikey mode with SEPAY_WEBHOOK_SECRET (default: the local test value).
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <PAYMENT_URL> <ORDER_CODE> <AMOUNT> [TX_ID]" >&2
  exit 2
fi

url="${1%/}"
code="$2"
amount="$3"
tx_id="${4:-$(date +%s%N)}"
secret="${SEPAY_WEBHOOK_SECRET:-local-test-sepay-secret}"
when="$(TZ=Asia/Ho_Chi_Minh date '+%Y-%m-%d %H:%M:%S')"

body=$(printf '{"id":%s,"gateway":"TestBank","transactionDate":"%s","accountNumber":"0000000000","code":null,"content":"%s chuyen tien","transferType":"in","transferAmount":%s,"accumulated":0,"subAccount":null,"referenceCode":"FT%s","description":"BankAPINotify %s chuyen tien"}' \
  "$tx_id" "$when" "$code" "$amount" "$tx_id" "$code")

curl -sS -X POST "$url/webhooks/sepay" \
  -H "Authorization: Apikey ${secret}" \
  -H "Content-Type: application/json" \
  -d "$body" \
  -w '\nHTTP %{http_code}\n'
