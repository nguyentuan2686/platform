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

## Recurrence (third local failure, F1.5b) and the proper fix (2026-11-26)
After the F1.4 fix, OutboxRelayIT failed locally again during F1.5b Task 1, twice in the same way. It was excluded from that verify, and the failing report was not kept, so which method failed is not on record.

Reading the class again showed the F1.4 fix had covered only `concurrent_relays_do_not_double_send`. The rest of the class still ran into the same timing traps:
- **Single-shot sends.** Five assertions were `relay.publishPending() == 1`, a single cycle on a fresh topic or right after `docker unpause`. That cycle has to succeed within a 2 s delivery timeout while three things happen first:
  - the broker auto-creates the topic;
  - the idempotent producer gets its producer id (InitProducerId);
  - after the unpause, the producer reconnects with backoff and bumps its epoch after the expired batch.
- **The crash test.** It expected the first cycle to throw. A send that timed out returns 0 before the crash hook runs, so the first cycle may not throw at all.
- **Exact counts under tight timeouts.** With a 2 s client timeout, a broker ack that arrives late is re-sent by the next cycle. The duplicate breaks the exact-count assertions `hasSize(1)`, `hasSize(2)` and `hasSize(200)` without any relay bug.
- **Consumer group join.** `consume()` subscribed with a fresh consumer group, so the group join counted against a 20 s deadline. It also ended on a 2 s "quiet period" rather than on the end state.

What was changed:
- `topic()` creates each topic up front with the AdminClient and waits until the partition has a leader.
- Every publish goes through `publishAll(relay)`, which cycles until no unpublished row remains, with a 50 s deadline.
- The crash test cycles until the crash hook has fired.
- Timeouts went from send/delivery 2 s and request 1 s to send/delivery 8 s and request 3 s, so a late ack cannot turn into a duplicate. Only `broker_down` pays the longer timeout.
- `consume()` assigns partition 0 directly, with no consumer group, and reads until its position reaches the end offset, with a deadline.

Result: `mvn -q -pl libs/java/platform-starter -Dit.test=OutboxRelayIT verify` passed 5 times out of 5 in a row on the 2-core machine. Each run took about 53 s for the class, down from about 139 s before because the quiet-period waits are gone.

In those runs the only failed send was the intended one during the broker pause. So the original flake was not reproduced and then shown fixed. The fix removes every timing assumption found in the class. If the test fails again, keep the failsafe report before rerunning.

Escalation: check. The class now waits only on observable end states, per the CLAUDE.md Testing rule.
