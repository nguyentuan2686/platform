# image-check hit a Maven Central transfer failure inside docker build

- Date: 2026-11-27
- Tags: build | infra

## Symptom
Jobs `payment-svc / image-check` and `reading-tuvi / image-check` failed in the same run with
`Non-resolvable parent POM ... spring-boot-starter-parent:pom:4.2.0 (absent): Could not transfer artifact`. All other jobs, including the
Maven `verify` jobs of the same services, passed on the same commit.

## Root cause
External flake: Maven Central unreachable for ~6 minutes from the Docker build (CLAUDE.md CI triage: "could not transfer artifact"
is an external flake). The Maven retry settings added for the `verify` jobs do not reach the Maven run inside the image build.

## Fix
No code change for this task: `gh run rerun --failed`. If it recurs, pass the same resolver retry settings into the Dockerfile Maven step.

## How to catch earlier
Not catchable locally; watch for a second occurrence in image-check specifically (ladder step 2).

## Escalation
none (first occurrence of this job; the verify-job variant is already at ladder step 3)
