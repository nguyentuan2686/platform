# Bootstrap: Argo CD redis image pull hit 429 on public.ecr.aws

- Date: 2026-11-28
- Tags: infra | build

## Symptom
In PR #62 (run 46134440883) both bootstrap jobs (x86 and ARM) failed with `timed out after 600s waiting for every Argo CD
Deployment/StatefulSet to be ready`. In the `bootstrap-diagnostics` artifact, `argocd-redis` was in ImagePullBackOff:
`public.ecr.aws/docker/library/redis:8.10.1-alpine ... 429 Too Many Requests`.

## Root cause
The public ECR registry rate-limited anonymous pulls from GitHub runners. This is an external flake and nothing to do with
the PR.

## Fix
`gh run rerun --failed`, no code change (CLAUDE.md CI triage rule).

## How to catch earlier
Read `events.txt` in the bootstrap-diagnostics artifact first. An ImagePullBackOff with 429 means rerun. If this registry
429 shows up again, mirror or pre-pull the redis image on the runner rather than rerunning.

## Escalation
none (first occurrence for public.ecr.aws)
