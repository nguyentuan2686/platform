# A privacy assertion matched the log line's own timestamp

- Date: 2026-11-26
- Tags: test

## Symptom
During the F1.5b final fix wave, a full web Vitest run failed two tests with no related code change:
- `lib/http.test.ts`: "logs only the upstream name and status".
- `app/vi/actions.test.ts`: "shows the unavailable state ... (no birth data)".

Both failed with "expected log not to contain '08:30'". The run happened at about 08:30 UTC.

## Root cause
Both tests assert that the birth time `08:30` never reaches a log line. They searched the whole serialized log line, and that line includes our own `timestamp`, `2026-11-26T08:30:…Z`. The tests failed for one minute a day, and for any timestamp holding `1990` or `1994` in the milliseconds part.

## Fix
Both tests strip the `"timestamp"` field before matching the forbidden values.

## How to catch earlier
A negative assertion ("never contains X") must search only the data under test, not fields we generate ourselves (time, ids). Otherwise it is a time bomb.

## Escalation
none (first occurrence).
