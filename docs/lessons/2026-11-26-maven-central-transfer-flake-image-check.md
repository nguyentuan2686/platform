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

## Follow-up (Plan 4a fix round 2): two resolver generations, two key families
In run 45875828397 the CI `verify` job still logged `attempt 1 of 4` (the default), while the Dockerfile builds used
the new retries. The runner image ships Maven 3.10.1, which bundles Maven Resolver 2.0.25. Resolver 2 reads
`aether.transport.http.retryHandler.*`. The Docker builds use `maven:3.9` (resolver 1.9), which reads
`aether.connector.http.retryHandler.*`. `.mvn/maven.config` now sets both families: 8 retries, 10s apart and
growing, about 6 min at most. Central had also kept answering 429 for longer than the earlier 3.5 min window.
How to catch earlier: check the retry evidence in the log (`attempt N of M`) on every runner and image that runs
Maven, not just one.

## Superseded (Plan 4a final fix wave, review I5)
The retry keys alone were never seen to rescue a run. The real fix: `.mvn/settings.xml` (Google's Central mirror first,
Central as fallback) and a cached dependency layer in each Java Dockerfile; see
`docs/lessons/2026-11-27-image-check-maven-central-flake.md` (Escalation).
