# Header balance stayed stale after a paid order on the real stack

- Date: 2026-11-26
- Tags: test, design

## Symptom
Full-stack e2e: the checkout page said "Đã nhận tiền" but the header chip still showed the old balance ("Còn 1 lượt" instead of 2). The stub-based e2e was green.

## Root cause
payment-svc marks the order PAID first; identity-svc grants credits a moment later from the `PaymentSucceeded` Kafka event. The page refreshed once, right at PAID, before the grant. The stub granted credits synchronously, so CI could not see it.

## Fix
`OrderStatus` refreshes at PAID and again after 2, 5 and 10 s. Unit test updated.

## How to catch earlier
The local full-stack run (real Kafka) before the PR. Stubs that grant synchronously hide async hand-offs.

## Escalation
none (first occurrence)

## Also noted
- Running `npm ci` in the Playwright (glibc) image leaves Linux/glibc native binaries in `services/web/node_modules`; the next alpine `vitest` run then hangs at "Timeout waiting for worker". Always `npm ci` again in the alpine image before the gates.
- The web build needs the knowledge corpus: the gates command mounts `knowledge/tuvi` and sets `KNOWLEDGE_DIR`.
