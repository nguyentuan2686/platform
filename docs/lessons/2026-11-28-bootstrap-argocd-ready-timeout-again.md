# bootstrap (x86) timed out waiting for Argo CD again (second occurrence)

- Date: 2026-11-28
- Tags: infra, ci

## Symptom
PR #51 run 46123841556 (reading-tuvi LLM adapter, no change under `infra/` or `deploy/argocd/`): `bootstrap / bootstrap (ubuntu-24.04)` failed after 11 min with `bootstrap: ERROR: timed out after 600s waiting for every Argo CD Deployment/StatefulSet to be ready`; the ARM job of the same commit passed, as did both bootstrap jobs of PR #57 queued at the same time. `ci-gate` failed.

## Root cause
Still not determined, for the same reason as the first time (`2026-11-27-bootstrap-argocd-ready-timeout.md`): on timeout the step prints no per-workload state. Same pattern: one architecture only, while other PRs competed for runners, so most likely a slow image pull or a throttled runner.

## Fix
None in code for this PR: it had to be rebased onto main anyway, which reran CI.

## How to catch earlier
As in the first lesson: a readiness timeout on one architecture with the other green on the same commit, on a PR that touches neither `infra/bootstrap/` nor `deploy/argocd/`, is a rerun.

## Escalation
Second occurrence. Per the first lesson, the next step is to make the readiness wait in `infra/bootstrap/lib/common.sh` print `kubectl get pods -o wide` and the namespace's recent events on timeout, so a third occurrence names its cause. That change touches `infra/bootstrap/` and is proposed as its own task (it needs its own bootstrap CI run and a `docs/system` line), not folded into this LLM PR.
