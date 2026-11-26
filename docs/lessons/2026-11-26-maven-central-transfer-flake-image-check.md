# reading-tuvi image-check: Maven Central "Could not transfer artifact" (registry flake)

- Date: 2026-11-26
- Tags: infra, build

## Symptom
PR #30 run 45813026975: `reading-tuvi / image-check` failed in the Docker build with
`Non-resolvable parent POM ... spring-boot-starter-parent:pom:4.2.0 (absent): Could not transfer artifact`.

## Root cause
Transient Maven Central download failure on the shared runner (no Maven cache inside the Docker build).
Not a code or workflow defect; the change under test did not touch reading-tuvi.

## Fix
None in code. The next push re-ran the job (the run was superseded by a real fix for another failure).

## How to catch earlier
CLAUDE.md CI triage rule: "could not transfer artifact" from a registry is an external flake; rerun.

## Escalation
check (Plan 4a fix round 1): the flake recurred in 4 of 6 runs of PR #32, so it was automated per ladder step 3. `.mvn/maven.config` makes the Maven resolver retry HTTP 429/500/502/503/504 six times, 10s apart and growing (about 3.5 min at most, against the default 3 retries over about 30s). It applies to every `mvn` run from the repo root: CI verify, the Dockerfile builds (`COPY . .` into /src) and local builds.

## Recurrence (PR #32, Plan 4a)
`Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:4.2.0`:
- run 45857609982: order-svc and payment-svc image-check
- run 45859850009: the k8s-e2e order-svc image build
The cause was external, and the jobs went green in later runs without any code change (CLAUDE.md CI triage).
