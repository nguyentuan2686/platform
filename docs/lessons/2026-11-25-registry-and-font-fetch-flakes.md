# identity-svc and web verify failed on external downloads (Maven Central 429, Google Fonts)

- Date: 2026-11-25
- Tags: infra, build

## Symptom
PR #25 run 45611996309 (commit f70827c, F1.4 final-review fixes): `ci-gate` failed because two jobs failed:
- `identity-svc / verify` failed while resolving dependencies. Maven Central answered "HTTP Status: 429" for `spring-boot-starter-mail:pom:4.2.0` and `nimbus-jose-jwt:pom:10.10`.
- `web / verify` failed in `next build`. `next/font/google` threw "TypeError: Cannot read properties of null (reading '1')" in `loader.js`. That is the font CSS download coming back empty or with an error.

The commit touched only reading-tuvi and `contracts/openapi/reading-tuvi.yaml`, and `reading-tuvi / verify` passed. The other jobs ran because a `contracts/**` change triggers every service.

## Root cause
Both were transient failures of external services that the shared GitHub runners call at build time. One was Maven Central rate limiting; the other was the Google Fonts fetch made by `next/font` at build time. Neither was a code or workflow defect.

## Fix
Re-ran the failed jobs (`gh run rerun --failed`). No code change.
- Rerun 1: `web / verify` went green. `identity-svc / verify` got 429 again on the same two POMs. They are identity-only dependencies, which are not in the shared `setup-java` Maven cache, so every run downloads them again.
- Rerun 2, after waiting 4 minutes: `identity-svc / verify` went green. `identity-svc / image-check` then got a 429 on `spring-boot-starter-parent:pom:4.2.0` inside the Docker build, which has no Maven cache.
- Rerun 3, after waiting 5 minutes: all green and `ci-gate` passed. Spacing the reruns out helps, because the rate limit lasts for minutes.

## How to catch earlier
Read the failing log before changing anything. These signs mean a rerun, not a code fix:
- HTTP 429/5xx from a package registry
- a `next/font` loader error
- any "could not transfer artifact"

## Escalation
rule. This is the second Maven Central 429 (first: `2026-11-24-maven-central-429-in-image-check.md`), so CLAUDE.md Workflow now holds the triage rule.

It can also be automated as a later step:
- Maven: `-Daether.connector.http.retryHandler.count=5` plus a dependency cache in CI.
- Web: self-host the fonts with `next/font/local` so the build makes no network call.

That is tracked as a follow-up and is not part of F1.4.

## Update 2026-11-26: second `next/font` failure, now automated
Main run 45819818131, job `k8s-e2e` "Build web" failed again with `An error occurred in next/font` (Google Fonts download during `next build`). Second occurrence, so per the ladder it became a hard check:
- Fonts are self-hosted: woff2 subsets (vietnamese + latin, from the `@fontsource` npm packages, OFL) in `services/web/src/fonts/`, declared in `src/styles/fonts.css` with the same unicode ranges and the same CSS variable names. `next/font/local` cannot express a unicode-range split across files, hence plain `@font-face`.
- `src/fontPolicy.test.ts` fails if any source file imports `next/font/google` or references a Google Fonts URL.
The build now makes no network call for fonts. The Maven Central part of this lesson is still tracked separately.
