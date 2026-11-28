# chart-svc log test failed at 08:30 UTC (needle matched the log timestamp)

- Date: 2026-11-28
- Tags: test

## Symptom
PR #58 run 46125976224: `chart-svc / verify` and `notify-svc / verify` (both run the workspace tests) failed in
`engine_failure_is_logged_once_without_the_birth`: "logs contain 08:30". The PR changed only cd.yml.

## Root cause
The test checks that the birth `1994-03-12T08:30` never reaches the logs by searching the raw log text for `08:30`
and `1994`. The run happened at 08:30 UTC, so the log line's own `timestamp` matched. "1994" can also appear in the
nanoseconds. A test that depends on the wall clock.

## Fix
The test parses each line and drops `timestamp` before searching for the birth.

## How to catch earlier
A "must not contain" assertion over logs must exclude fields the test does not control (timestamps, ids).

## Escalation
none (first occurrence)
