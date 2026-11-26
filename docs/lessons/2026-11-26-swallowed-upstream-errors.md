# Upstream errors swallowed without a log (twice in F1.5b)

- Date: 2026-11-26
- Tags: design | process

## Symptom
F1.5b review found the same defect twice:
- Task 2: page parts degraded on any identity error with no log line.
- Task 4: upstream 4xx answers were turned into a "thử lại sau" message with nothing logged. This hit `mua/actions.ts`, `api/dev/sepay/route.ts` and `giao-dich` `refundAction`.

A wrong internal token, a contract mismatch or a real bug (`TypeError`) looked exactly like "identity is down" and left no trace. The final review found a third instance still on the branch: `lib/login.ts` wrapped `mergeGuest` in a bare `catch {}`, so any error became a "qa merge failed" warning, bugs included, and the warning did not say which error kind it was.

## Root cause
"Degrade gracefully" was implemented as `catch { return fallback }`. `http.ts` logs only `UpstreamError` (down, 5xx, timeout). A 4xx or a non-upstream exception caught this way vanishes. The shared helpers `degradeOrThrow` and `logRefusalOrThrow` (`lib/degrade.ts`) existed after Task 2, but nothing forced new code to use them.

## Fix
- `lib/login.ts` rethrows anything that is not an upstream error and logs the upstream name and status.
- Every other bare catch either goes through a shared helper or says why it is safe with a `catch-ok: <reason>` comment. Those are browser code with no server log, parsing untrusted input, or a missing browser API.

## How to catch earlier
A catch block is the place to look in review. Ask whether a bug thrown here would still reach the error boundary or a log.

## Escalation
check:
- CLAUDE.md rule under "Java conventions / web" (no empty or log-less catch).
- `services/web/eslint.config.mjs` sets `no-empty` with `allowEmptyCatch: false`.
- `services/web/src/catchPolicy.test.ts` parses `src/**` with the TypeScript compiler. It fails on any catch that does not rethrow, does not call `log` / `logRefusalOrThrow` / `degradeOrThrow` / `upstreamFailure` / `upstreamMessage`, and does not carry a `catch-ok:` reason.
