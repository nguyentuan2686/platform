# Mailpit crash-looped in the kind e2e: read-only root filesystem without /tmp

- Date: 2026-11-26
- Tags: infra, test

## Symptom
PR #30 run 45808392008, job `k8s-e2e / kind-e2e`: every app became Available and answered
`/health/ready`, but `scripts/k8s-e2e.sh` failed with `FAIL: http://localhost:8025/readyz did not answer
within 180s: curl: (52) Empty reply from server`. Diagnostics artifact: `pod/mailpit` in CrashLoopBackOff,
log `[db] open /tmp/mailpit-<n>.db: read-only file system`.

## Root cause
`deploy/k8s/overlays/e2e/mailpit.yaml` (Task 4) set `readOnlyRootFilesystem: true` but mounted no
writable `/tmp`. Mailpit without `--database` keeps its message store in a temp file under `/tmp`.
The app bases all carry the `/tmp` emptyDir; the Mailpit manifest was written separately and missed it.
Static checks (kubeconform, policy) cannot see what a container writes at runtime, and `k8s-e2e.sh`
did not wait on the Mailpit rollout, so the failure surfaced late as a port-forward timeout.

## Fix
Mailpit gets an `emptyDir` at `/tmp`. `k8s-e2e.sh` now also waits on `rollout status deploy/mailpit`,
so a crash-looping dependency fails at the wait step with a clear message.

## How to catch earlier
Every container with `readOnlyRootFilesystem: true` needs a writable mount for each path it writes
(at least `/tmp`). Check the image's write paths when hardening a third-party container, and wait on
every deployment the smoke scripts depend on, not only the apps.

## Escalation
none (first occurrence)
