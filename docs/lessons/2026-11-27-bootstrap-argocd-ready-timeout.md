# bootstrap (x86) timed out waiting for Argo CD to become ready

- Date: 2026-11-27
- Tags: infra, ci

## Symptom
PR #39 run 45967815249 (Plan 5, no change under `infra/` or `deploy/argocd/`): `bootstrap / bootstrap (ubuntu-24.04)` failed with `bootstrap: ERROR: timed out after 600s waiting for every Argo CD Deployment/StatefulSet to be ready`; the ARM job of the same commit passed in 3m16s. `ci-gate` failed.

## Root cause
Not determined: the step prints no per-workload state on timeout, so the log cannot say which Argo CD pod was not ready. Most likely a slow or throttled image pull (quay.io / ghcr.io) on that runner, since the same pinned manifests came up in about 3 minutes on ARM and on the rerun.

## Fix
`gh run rerun --failed`: the x86 job passed in 3m30s and `ci-gate` went green. No code change.

## How to catch earlier
A bootstrap readiness timeout on one architecture only, with the other green on the same commit, is a rerun. Before changing anything, check whether the PR touched `infra/bootstrap/` or `deploy/argocd/`.

## Escalation
none (first occurrence). If it recurs: make `wait_until` in `infra/bootstrap/lib/common.sh` print `kubectl get pods -o wide` and recent events of the namespace on timeout, so the second occurrence names its cause.
