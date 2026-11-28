# First real verify-deploy failed: prod-apps stuck Progressing (Ingress health), and the verdict was lost

- Date: 2026-11-28
- Tags: infra | test

## Symptom
The first `cd` runs with Tailscale configured (after PRs #50 and #48) failed verify-deploy after the full 900 s deadline:
`FAIL argo-prod-apps: sync=Synced health=Progressing`, while prod-platform and prod-tunnel were OK and the live site
worked (issue #53). The issue also said "verify-deploy did not run to a verdict (rc=none)" although the script exited 1.

## Root cause
1. Argo CD's built-in Ingress health is Progressing until `status.loadBalancer.ingress` is set. k3s's Traefik is a
   ClusterIP Service behind cloudflared (by design, no public ports), so no Ingress ever gets that status and prod-apps
   can never be Healthy. CI never saw it: the bootstrap job leaves prod-apps unsynced (CI mode), the kind e2e does not
   go through Argo CD, and verify-deploy had never run against prod before Tailscale was set up.
2. The verify step in `cd.yml` runs under GitHub's `bash -e`; `set -uo pipefail` keeps `-e`, so the failing
   `verify-deploy.sh | tee` pipeline ended the step before `rc=` was written. Without rc the rollback logic falls to
   "no verdict": an unhealthy deploy is never reverted.
3. verify-deploy printed only the app-level health, so the failing resource was invisible without cluster access.

## Fix
- `infra/bootstrap/argocd-cm-patch.yaml`: Ingress health customization (Healthy when it has rules); takes effect after
  `sudo infra/bootstrap/bootstrap.sh` on the host.
- `scripts/verify-deploy.sh`: on FAIL, list the app's non-Healthy resources (`unhealthy <kind>/<ns>/<name>: ...`).
- `cd.yml`: capture rc with `set +e` (separate PR, merged only after the host runs the new bootstrap, so the first
  verdict after it is a real one and not a rollback of a healthy deploy).

## How to catch earlier
A check that is gated on secrets (verify-deploy) has never run until those secrets exist: run it once by hand (or
`workflow_dispatch`) right after setting them, before relying on it. Keep the step's own exit-code capture under `set +e`.

## Escalation
none (first occurrence)
