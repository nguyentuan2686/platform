# 4. Kubernetes manifests and observability

Date: 2026-11-26

## Status
Accepted (plan 3 in `docs/superpowers/plans/`)

## Context
The services run only in docker compose. Plan 4 deploys to k3s with Argo CD, so we need cluster manifests, a metrics path that works for Java and Rust alike, pod logs in one place, and a CI check that runs on a weak dev machine that never runs kind.

## Decision
- Kustomize only (no Helm): `base/<svc>`, `platform/`, `overlays/{prod,e2e}`. Namespaces `apps`, `data`, `observability`.
- Metrics are pushed over OTLP (services -> otel-collector -> otel-lgtm). No `/prometheus` scraping of services. The only scrape is Redpanda's `/public_metrics`, done by the collector.
- Consumer lag is measured by Redpanda (`enable_consumer_group_metrics`), not by each client, so it is language-neutral.
- otel-lgtm runs as a single pod with one 5Gi PVC for now. Splitting Grafana/Loki/Tempo/Prometheus or tuning retention is left to Plan 4.
- Pod logs are read from `/var/log/pods/apps_*` by a collector DaemonSet (`filelog`, JSON parsed to severity, trace context, `service.name`). Checkpoints are not persisted (`start_at: end`), so a collector restart can skip lines written meanwhile; acceptable at this stage.
- The collector is the only pod that is forced to run as root (`runAsUser: 0`), because the pod log files are root-owned and mounted through a read-only hostPath. It drops all capabilities and has a read-only root filesystem. Postgres and lgtm set no user and so run as their images' defaults (the lgtm image runs as root; the postgres entrypoint starts as root and drops to `postgres`); the numeric non-root rule is enforced for the `apps` namespace only.
- The base is prod-safe (`DEPLOY_ENV=prod`, simulator off, secure cookies). Secrets arrive by name only (`postgres-secrets`, `<svc>-secrets`); no `kind: Secret` is committed. Plan 4 supplies SealedSecrets; the e2e overlay generates test-valued ones.
- The e2e job runs the full stack (platform plus apps) in kind in CI; the dev machine only runs `scripts/k8s-validate.sh` in Docker.
- `scripts/k8s-validate.sh` is the local and CI gate: kubeconform (strict, pinned Kubernetes version) plus jq policy checks (memory request == limit, no CPU limit, probes, startup budget for Java, numeric non-root, no `latest`, no Secret in prod) with fixtures under `scripts/testdata/k8s-validate/` proving each check fails. CI also runs a Trivy config scan at CRITICAL.

## Consequences
Metrics and logs have one pipeline for every language, at the cost of a collector DaemonSet and a root pod. A single lgtm pod is a single point of failure and a memory-tight 1 GiB; data loss on PVC loss is accepted until Plan 4. Policy checks are our own jq, so new rules need a fixture each.
