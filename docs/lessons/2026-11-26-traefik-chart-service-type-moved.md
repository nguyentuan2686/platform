# Traefik chart moved the Service type: `service.type` silently ignored

- Date: 2026-11-26
- Tags: infra

## Symptom
In the first `bootstrap` CI run on PR #32 (run 45856436147), both legs (x86 and ARM) failed `verify.sh` with
`FAIL traefik-clusterip: type is LoadBalancer`. The HelmChartConfig had been applied and its values were passed to Helm.

## Root cause
k3s v1.37 bundles Traefik chart 41.x. That chart takes the Service type under `service.spec.type`. The older key
`service.type`, which our HelmChartConfig used (and the plan text quotes), no longer exists. Helm ignores unknown
values without a warning, so the install succeeded and the Service stayed `LoadBalancer`. With servicelb disabled,
nothing was exposed in practice, but the defence-in-depth setting was not in effect.

## Fix
`infra/bootstrap/traefik-config.yaml` sets `service.spec.type: ClusterIP`.

## How to catch earlier
When pinning a bundled chart's version, read the `values.yaml` of that exact chart version for every key we override.
Assert the resulting object state (`verify.sh traefik-clusterip`), not just "values applied". That check caught it here.

## Escalation
none (first occurrence; the verify check already guards it)
