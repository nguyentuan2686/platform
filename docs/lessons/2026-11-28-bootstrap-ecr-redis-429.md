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
Rerun (`gh run rerun --failed`). This was the second Argo CD readiness timeout (the first, PR #58, had no root cause:
lesson 2026-11-28-bootstrap-x86-argocd-ready-timeout), so the bootstrap now pre-pulls every image in the pinned Argo CD
install.yaml through the CRI with backoff (10..160 s) before applying it (`prepull_images` in infra/bootstrap/lib/argocd.sh).
The first version of that still failed: ECR kept answering 429 for over 5 minutes on the ARM runner, and the redis
container has `imagePullPolicy: Always`, so even a pre-pulled image would be pulled again. Final fix: a Docker official
image from public.ecr.aws falls back to mirror.gcr.io/library (same image) and is tagged back to its original name, and
the applied install.yaml uses `IfNotPresent` (every tag in it is pinned).

## How to catch earlier
Read `events.txt` in the bootstrap-diagnostics artifact first: ImagePullBackOff with 429 means a registry rate limit.

## Escalation
check: `prepull_images` in the bootstrap (PR #64), exercised by every CI bootstrap job.
