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

## Recurrence (2026-11-27, Task 7, run 45893077574)
`payment-svc / image-check` failed again with the same 429 `Too Many Requests` from Maven Central on
`spring-boot-starter-parent:pom:4.2.0`; every other job passed. Handled as an external flake (`gh run rerun --failed`).
Second occurrence: the CLAUDE.md CI-triage rule already covers it (ladder step 2). Since it recurs inside the docker
build, the next step is a real fix (longer/exponential retry window in the image build, or a Maven cache shared with
the verify job), tracked for the plan's final fix wave rather than done inside an unrelated task.

## Recurrence (2026-11-27, Task 8, run 45899391121)
A new variant in a Maven `verify` job: `identity-svc / verify` failed with `Could not find artifact
org.springframework.boot:spring-boot-starter-mail:jar:4.2.0 in central` and the same for `com.nimbusds:nimbus-jose-jwt:jar:10.10`
(`The POM ... is missing`), with no 429 in the log. Both artifacts exist and resolved in the previous green run (45896150778) on an
unchanged `pom.xml`, and the commit touched no Java. Central answered "not found" for a moment instead of 429/5xx, so the resolver retries
(429/5xx only) did not apply. Handled as an external flake (`gh run rerun --failed`). This adds weight to the open real fix (a Maven cache
or mirror) for the final fix wave; a not-found from Central cannot safely be retried by status code alone.
