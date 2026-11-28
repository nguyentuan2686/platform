# Scaffold test pinned the real service list

- Date: 2026-11-28
- Tags: test

## Symptom
PR #62 failed CI job `ops-scripts`: `FAIL java_renders_and_registers: ' payment-svc web demo-java; do # new-service: e2e-load'
appears 0 times in .github/workflows/_k8s-e2e.yml (want 1)`.

## Root cause
`scripts/test/new-service.test.sh` asserted the rendered kind image-load loop with the literal "previous last service"
(`web`). The first real service scaffolded after web (astro-chart-svc) became the new last word, so the expectation
no longer matched even though the scaffolder appended correctly. The PR did not run `scripts/test/*.test.sh` locally.

## Fix
The assertion now expects the demo name right after the current last service (`astro-chart-svc`). Ran
`bash scripts/test/new-service.test.sh` locally: ok.

## How to catch earlier
A PR that registers a new service runs `bash scripts/test/new-service.test.sh` before pushing (it is cheap: bash/sed/awk).
If this recurs, derive the expected word from the marker line on the base checkout instead of a literal.

## Escalation
none (first occurrence)
