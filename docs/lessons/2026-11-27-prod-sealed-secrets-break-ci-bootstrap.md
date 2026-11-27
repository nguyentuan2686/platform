# Real prod SealedSecrets in Git broke the CI bootstrap job

- Date: 2026-11-27
- Tags: infra | test

## Symptom
The first PR that committed the real prod SealedSecrets (sealed for the prod host's key) failed `bootstrap` on both
runners: `FAIL secret-materialised: apps/order-svc-secrets [] want [DB_PASSWORD,DB_USER]` for all 7 required secrets.

## Root cause
In CI mode the child Applications follow the PR revision, so `prod-sealed` synced the Git SealedSecrets, which only the
prod key can decrypt. The job's own test secrets (sealed with the runner's throwaway key, applied with `kubectl apply`)
had the same names, and `prod-sealed`'s selfHeal put the undecryptable Git copies back over them. Until this PR the
sealed directory held no SealedSecrets, so the conflict never showed.

## Fix
`render_root` (CI mode only) patches `prod-sealed` with `ignoreDifferences` on SealedSecret `/spec` and
`RespectIgnoreDifferences=true`, so the CI-sealed copies stay. `scripts/test/bootstrap-root.test.sh` asserts the patch
in CI mode and its absence otherwise.

## How to catch earlier
When a CI job and a GitOps sync both write the same object, decide which wins before the Git side gets real content;
a test fixture that is empty in Git hides the race.

## Escalation
none (first occurrence)
