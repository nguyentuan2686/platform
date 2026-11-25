# Runbook: payments (payment-svc + identity-svc)

Local test values live in `deploy/local/.env.example`; real values only via Sealed Secrets. Operator endpoints (`/admin/**`) need the `X-Admin-Token` header (`ADMIN_API_TOKEN`) and are not exposed publicly. Log ids only (order code, provider tx id), never transfer content or account numbers.

## 1. A transfer shows as "chua khop" (unmatched)
A SePay transfer that matches no order (wrong or missing code, underpayment, unknown code) is stored as an unmatched transaction and no credits are granted. Partial transfers are never summed.
1. List: `GET /admin/unmatched-transactions` with `X-Admin-Token`.
2. Find the customer's order by its code (customer shows the code or the bank receipt) via `GET /orders?account_id=...`.
3. Right order and amount: `POST /admin/unmatched-transactions/{id}/assign` with `{"order_code": ...}`. Refused with 409 `order_not_pending` or `amount_low`. Otherwise the order becomes PAID and PaymentSucceeded is published (identity-svc grants the credits/pass).
4. Underpaid: ask the customer to transfer the difference with a **new order** for the product, or refund the transfer manually in the bank app and `POST /admin/unmatched-transactions/{id}/resolve` with `resolution` and an optional `note`.
5. Not ours (random deposit): `resolve` with `resolution` and an optional `note`.
Overpayment is not "chua khop": the order is PAID and the surplus is noted on it (refund the surplus manually if the customer asks).

## 2. Manual refunds (SePay)
Money refunds are only for **unused credit packs** (`CREDIT_1`, `CREDIT_5` with nothing consumed). Passes are refunded manually, outside the system.
1. The customer asks via `POST /orders/{id}/refund-requests`. identity-svc takes the credits back first (409 `credits_used` or `not_refundable` otherwise); then the order becomes REFUND_REQUESTED.
2. `GET /admin/refund-requests` lists the queue.
3. Send the money back in the bank app (SePay has no refund API), then `POST /admin/refund-requests/{id}/complete` (optional body). The request becomes DONE and the order REFUNDED. PayPal refunds are immediate and need no operator step.
4. Credits already spent: the request is refused with 409 `credits_used`; decline to the customer.

## 3. Switch the SePay webhook auth mode
`SEPAY_WEBHOOK_AUTH` is `apikey` (header `Authorization: Apikey <secret>`) or `hmac-sha256` (header `X-SePay-Signature`). Use whichever your SePay dashboard offers.
1. In SePay set the matching auth and secret; update the Sealed Secret `SEPAY_WEBHOOK_SECRET` and the env `SEPAY_WEBHOOK_AUTH`.
2. Roll payment-svc. Until both sides agree webhooks answer 401 and SePay retries; nothing is lost, and reconciliation (below) picks up misses.
3. Check with `scripts/sepay-simulate.sh` locally (apikey mode) and a real 1 000 VND test transfer in production.

## 4. Enable reconciliation (lost-webhook safety net)
Off by default (`SEPAY_RECONCILE_ENABLED=false`). It lists SePay transactions of the last 24 h every `SEPAY_RECONCILE_INTERVAL` and feeds them through the webhook path (idempotent on the provider tx id).
1. **First** verify the real `transactions/list` response shape of your SePay account against the adapter (field names, amount type, pagination); the adapter was written against the public docs only.
2. Set `SEPAY_API_BASE_URL` (default `https://my.sepay.vn`) and `SEPAY_API_TOKEN` (Sealed Secret).
3. Set `SEPAY_RECONCILE_ENABLED=true`, roll payment-svc, watch logs for one interval: matched/duplicate counts, no errors.
4. Roll back by setting it to `false`.

## 5. Kafka topics
`payments.payment-succeeded.v1` and `payments.payment-succeeded.v1.DLT` are declared by both services (`KafkaTopicsConfiguration`, created by `KafkaAdmin` at startup). Messages on `.DLT` are contract errors (unknown product or account, bad envelope): inspect with `rpk topic consume payments.payment-succeeded.v1.DLT`, fix the cause, and republish by hand if needed.
