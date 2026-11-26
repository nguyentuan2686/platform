# Alerts runbook

Rules live in `deploy/k8s/platform/grafana/alerting/rules.yaml` (Grafana provisioning, folder `Platform`, group `platform`, every 1m). Their PromQL is unit-tested with `bash scripts/alerts-promtool.sh`. Notifications go to the Telegram contact point `telegram` only when the optional `alerting-secrets` Secret (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) exists; without it the rules still evaluate and show in Grafana (Alerting -> Alert rules). A rule with no data stays OK (`noDataState: OK`); a failing query is an error (`execErrState: Error`).

Query the data in Grafana Explore (Prometheus datasource, Loki for logs, Tempo for traces).

## error-rate-high
Severity critical. More than 5% of requests to a service (excluding `/health/*` and the long-running `/tuvi/qa/readings*` routes) answered 5xx over 5m, for 1m, and the service handles more than 0.01 req/s (so an idle service with one failure stays quiet).
- First queries: `sum by (job, http_route, http_response_status_code) (rate(http_server_request_duration_seconds_count{http_response_status_code=~"5.."}[5m]))`; Loki `{service_name="<job>"} |= "ERROR"`; Tempo traces with `status = error` for the service.
- Likely fixes: a bad deploy (check `kubectl -n apps rollout history deploy/<job>` and revert the image tag in `deploy/k8s/overlays/prod/apps/kustomization.yaml`), a down dependency (Postgres, Redpanda, another service), exhausted DB connections.

## latency-p95-high
Severity warning. p95 of `http_server_request_duration_seconds` above 1s for 5m on a service (same route exclusions).
- First queries: `histogram_quantile(0.95, sum by (job, http_route, le) (rate(http_server_request_duration_seconds_bucket[5m])))` to find the slow route, then slow traces in Tempo.
- Likely fixes: a slow query or upstream call, CPU throttling or memory pressure on the pod (`kubectl -n apps top pod`), a cold start after a restart (wait), a missing index.

## pod-restart-loop
Severity critical. A container restarted 3 or more times within 15m (fires immediately).
- First queries: `max by (k8s_namespace_name, k8s_pod_name) (delta(k8s_container_restarts[15m]))`; `kubectl -n <ns> describe pod <pod>` (last state, reason) and `kubectl -n <ns> logs <pod> --previous`.
- Likely fixes: OOMKilled (raise the memory request and limit together), a failing readiness or startup probe, a crash on a missing or wrong secret (compare with `deploy/k8s/overlays/prod/secrets.tsv`), a bad image.

## consumer-lag-rising
Severity warning. A Redpanda consumer group has lag above 100 messages and growing for 10m.
- First queries: `redpanda_kafka_consumer_group_lag_max` by `redpanda_group`; the Platform / Messaging dashboard (processing duration and outbox lag); the consumer's logs.
- Likely fixes: the consumer is down or crash-looping (see `pod-restart-loop`), a poison message that is retried forever, a slow downstream dependency; scale or fix the consumer, then watch the lag drain.

## disk-usage-high
Severity critical. The node's root filesystem is more than 80% used for 10m (`system_filesystem_utilization_ratio{mountpoint="/"}`, from the collector's hostmetrics receiver reading the host `/`).
- First queries: `max by (mountpoint) (system_filesystem_utilization_ratio)`; on the node `df -h /` and `du -xh --max-depth=2 / | sort -h | tail`.
- Likely fixes: prune unused images (`k3s crictl rmi --prune`), shrink container logs, check that Prometheus, Loki and Tempo retention is in effect (15d, 168h, 168h) and the telemetry volume `lgtm-data` is not the culprit, remove old files in `/var/lib/rancher`; if the disk is simply too small, grow it.

## deploy-failed
Severity critical. An Argo CD application has been `Degraded` or `Missing` for 10m (`argocd_app_info{health_status=~"Degraded|Missing"}`, scraped from `argocd-metrics.argocd.svc.cluster.local:8082`).
- First queries: `argocd app get <name>` or the Argo CD UI (which resource is unhealthy), `kubectl -n argocd get applications`, `scripts/verify-deploy.sh --app <name>`.
- Likely fixes: an invalid manifest or missing Secret (for example a sealed secret that was never applied), a failing rollout (see `pod-restart-loop`), a CD commit that should be reverted. No data at all for this rule (for example Argo CD is not installed) is not an alert.
