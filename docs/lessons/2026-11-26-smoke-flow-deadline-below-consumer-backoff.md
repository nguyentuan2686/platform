# smoke-flow deadline (15s) below the consumer's retry backoff (30s)

- Date: 2026-11-26
- Tags: test

## Symptom
k8s-e2e on PR #32 (run 45857609982):
- `FAIL: no notification for order ... within 15s`
- All pods were Running with 0 restarts.
- notify-svc logged `kafka receive failed, retrying` with `backoff_ms: 30000`.

## Root cause
Plan 4a Task 2 gave notify-svc a `wait-for-postgres` init container. notify-svc no longer crash-loops until the whole
data tier is up. It now starts as soon as Postgres answers, often before Redpanda. Its consumer then backs off
exponentially (1s doubling to platform-core's `DEFAULT_MAX_BACKOFF` = 30s). When the e2e placed the first order, the
consumer could still sleep for up to 30s. smoke-flow waited only 15s. This was a tight timeout turning a slow first
call into a failure (CLAUDE.md Testing), exposed by the Task 2 startup-order change.

## Fix
`scripts/smoke-flow.sh` waits up to 60s for the notification. That is above the maximum backoff, and the loop still
ends on the observable state.

## How to catch earlier
A test deadline that waits on an async consumer must exceed that consumer's maximum retry backoff. Check the client's
backoff when choosing a deadline.

## Escalation
none (first occurrence)
