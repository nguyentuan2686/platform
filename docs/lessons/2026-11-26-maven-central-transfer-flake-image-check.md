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
none (rule already in CLAUDE.md, see 2026-11-25-registry-and-font-fetch-flakes.md)
