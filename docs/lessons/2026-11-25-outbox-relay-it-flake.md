# OutboxRelayIT.concurrent_relays_do_not_double_send flaked under load

- Date: 2026-11-25
- Tags: test

## Symptom
During two separate F1.4 verifies, `libs/java/platform-starter` `OutboxRelayIT.concurrent_relays_do_not_double_send` failed; it passed on rerun with no change. Not reproduced locally afterwards, so the cause below is a reading of the code, not an observed trace.

## Root cause
Each relay thread looped `while (publishPending() > 0)`. `publishPending()` returns 0 both when nothing is pending and when the first send of a batch fails. The test configures very tight Kafka timeouts (`send-timeout=2s`, `delivery.timeout.ms=2000`) and the topic is auto-created on the first send. On the 2-core machine (and a busy CI runner) that first send can exceed 2 s, so the relay logs a warning, publishes 0 and the loop ends early. Rows stay pending and `published == 200` fails, although the relay behaved correctly (it is built to retry later).

## Fix
The loop now runs until no unpublished row remains (or a 50 s deadline), sleeping 200 ms after a 0, instead of stopping on the first 0. The assertions (200 published, 200 distinct event ids, none duplicated) are unchanged.

## How to catch earlier
Do not use "returned 0" as "done" for an operation that returns 0 on failure; wait on the observable end state (no pending rows) with a deadline. A test with tight timeouts that is shared with warm-up-sensitive resources (fresh topic) needs a deadline-based wait, not a fixed number of attempts.

## Escalation
rule: second occurrence of a timing flake in tests (see also the tracing flake lessons); added to `CLAUDE.md` (Testing rule).
