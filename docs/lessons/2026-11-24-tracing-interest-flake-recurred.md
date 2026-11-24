# Traceparent test flaked again despite rebuild_interest_cache

- Date: 2026-11-24
- Tags: test

## Symptom
PR #8, run 45297923248, `notify-svc / verify` (`cargo llvm-cov`): `traceparent_header_sets_handler_trace_id_and_span_attrs` in `libs/rust/platform-core/tests/http.rs` failed again with `left: ""`, `right: "4bf92f35..."`. It passed locally under `test` and `llvm-cov`. This is the second occurrence; see `2026-11-24-tracing-callsite-interest-flaky-test.md`.

## Root cause
The test still used a thread-local `set_default` subscriber in a binary whose other tests run in parallel and hit the same request-span callsite (`trace_layer`). `tracing` callsite interest and the max level hint are process-wide. Calling `rebuild_interest_cache()` once narrows the window, but a concurrent first registration of that callsite can still leave it cached as "never" for this subscriber. The span is then disabled, `set_parent` does nothing, and the handler sees no trace context.

## Fix
I moved the test to its own binary, `libs/rust/platform-core/tests/http_trace.rs`, which has exactly one test. It installs a global subscriber (`set_global_default`) before any span is created, so there is nothing left to race.

## How to catch earlier
A test that asserts on spans or trace context from library code must not share a binary with parallel tests. Use one test binary per global-subscriber test, as `telemetry_no_exporter.rs` and `otlp_init.rs` already do.

## Escalation
rule: added to `.claude/skills/rust-service-conventions/SKILL.md` (Test pyramid section).
