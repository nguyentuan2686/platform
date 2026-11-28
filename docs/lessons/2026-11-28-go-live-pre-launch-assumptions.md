# Go-live broke checks that assumed "no domain yet"

- Date: 2026-11-28
- Tags: infra | test | process

## Symptom
PR #45 (APP_DOMAIN=xemtuvingay.com, named Cloudflare tunnel, sealed `cloudflared-secrets`) failed CI twice:
1. `ops-scripts`: `web-origin.test.sh` asserted the committed `domain.yaml` is still `app.example.invalid`.
2. `bootstrap` (x86 + ARM): Argo CD `root` stayed OutOfSync/Degraded. `cloudflared-secrets` was `optional` in `secrets.tsv`, so the CI bootstrap did not seal an e2e value for it; the prod SealedSecret (prod key) could not be unsealed by the CI key, and the job also asserted the secret must be absent.

After the fix, the ARM bootstrap failed once more on the update-branch merge commit with `second-run-no-changes: CHANGED gitops-root` (the second bootstrap run saw a different revision). Same code had passed on both arches one run earlier; a rerun passed.

## Root cause
Checks written before go-live encoded the pre-launch state (placeholder domain, quick tunnel) as an invariant instead of as one allowed state. The named tunnel made `cloudflared-secrets` required, but its inventory row stayed `optional`.

## Fix
`cloudflared-secrets` is `required` in `secrets.tsv` with an e2e value (`local-test-tunnel-token`); the bootstrap job no longer expects it absent; the web-origin test accepts any https origin or empty for the committed file.

## How to catch earlier
When a change flips a "before go-live" switch, grep the tests and workflows for the old value (`example.invalid`, `cloudflared-quick`, `optional`) before pushing. The ARM revision mismatch is a first occurrence: rerun once; if it recurs, pin the bootstrap revision to `github.sha` for both runs.

## Escalation
none (first occurrence)
