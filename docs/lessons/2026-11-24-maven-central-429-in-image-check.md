# order-svc image-check failed on Maven Central HTTP 429

- Date: 2026-11-24
- Tags: infra, build

## Symptom
PR #8 run 45286977807: `order-svc / image-check` failed inside the Docker build (`mvn package`) with "Non-resolvable parent POM ... spring-boot-starter-parent:4.2.0 ... status code: 429, Too Many Requests" from repo.maven.apache.org. `ci-gate` failed as a consequence. The PR did not touch order-svc sources; it ran because `.github/**` changes trigger all services.

## Root cause
Transient rate limiting by Maven Central of the shared GitHub runner IP range. Not a code or workflow defect.

## Fix
Re-ran the failed jobs (`gh run rerun --failed`); all green on the second attempt. No code change.

## How to catch earlier
Cannot be caught earlier; classify by reading the log before changing anything (429/5xx from a package registry = rerun). If it recurs, add a BuildKit cache mount or a registry mirror for the Maven cache, or retry flags (`-Daether.connector.http.retryHandler.count`).

## Escalation
none (first occurrence)
