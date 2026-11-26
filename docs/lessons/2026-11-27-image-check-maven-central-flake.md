# image-check hit a Maven Central outage inside docker build

- Date: 2026-11-27
- Tags: build | infra

## Symptom
Jobs `payment-svc / image-check` and `reading-tuvi / image-check` failed in the same run with
`Non-resolvable parent POM ... spring-boot-starter-parent:pom:4.2.0 (absent): Could not transfer artifact`. All other jobs, including the
Maven `verify` jobs of the same services, passed on the same commit.

## Root cause
Upstream outage: Maven Central was unreachable for about 6 minutes (CLAUDE.md CI triage: "could not transfer artifact" is an external
flake). The Maven retry settings do reach the image build (the Dockerfiles `COPY . .` before `mvn`, so `/src/.mvn/maven.config` with 8
retries at 10s intervals applies), but that retry window (about 80s) is shorter than the outage.

## Fix
No code change: `gh run rerun --failed` (it did not recur on the next run).

## How to catch earlier
Not catchable locally. If it recurs, the options are a longer or exponential retry window, or a registry mirror/cache, not passing the
retry settings again.

## Escalation
none (first occurrence of this job; the verify-job variant is already at ladder step 3)
