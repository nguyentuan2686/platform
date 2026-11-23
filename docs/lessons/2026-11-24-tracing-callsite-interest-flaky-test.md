# Flaky platform-core traceparent test: cached tracing callsite interest

- Date: 2026-11-24
- Tags: test

## Symptom
PR #8, `notify-svc / verify` failed in `cargo llvm-cov` on `traceparent_header_sets_handler_trace_id_and_span_attrs` (`tests/http.rs`): the handler saw an empty trace id (`left: ""`, `right: "4bf92f35..."`). The identical code had passed on the previous CI run (cold cache) and passes locally.

## Root cause
The test installs a thread-local subscriber with `tracing::subscriber::set_default`. `tracing` caches each callsite's "interest" process-wide on first use. Tests in the same binary run in parallel; if another test hit the request-span callsite first while no subscriber was active, the callsite cached "never" and this test's subscriber never received the span, so no OpenTelemetry context existed. Timing-dependent, hence flaky (timing differs under llvm-cov instrumentation and with a warm cache).

## Fix
Call `tracing::callsite::rebuild_interest_cache()` right after `set_default` in the test, so the new subscriber is consulted.

## How to catch earlier
Any test relying on `set_default` plus spans created by library code should rebuild the interest cache. Treat a test that passes once and fails on a rerun of the same SHA as a race, not infrastructure noise.

## Escalation
none (first occurrence; add a rule to rust-service-conventions if it recurs)
