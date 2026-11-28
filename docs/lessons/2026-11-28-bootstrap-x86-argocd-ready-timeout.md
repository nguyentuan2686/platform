# bootstrap (x86) timed out waiting for Argo CD workloads

- Date: 2026-11-28
- Tags: infra | test

## Symptom
PR #58 run 46125976224: `bootstrap / bootstrap (ubuntu-24.04)` failed with "timed out after 600s waiting for every
Argo CD Deployment/StatefulSet to be ready". The ARM job of the same run and the main runs before passed; the PR did not
touch the bootstrap.

## Root cause
Not determined: a slow x86 runner or slow image pulls for Argo CD (the log has no pod events).

## Fix
`gh run rerun --failed`.

## How to catch earlier
If it recurs: print `kubectl -n argocd get pods` and events on timeout in `wait_until` for this step, then decide
between a longer deadline and pre-pulling images.

## Escalation
none (first occurrence)
