# Alerts runbook

Rules live in `deploy/k8s/platform/grafana/alerting/rules.yaml` (Grafana provisioning, folder `Platform`, group `platform`, every 1m). Their PromQL is unit-tested with `bash scripts/alerts-promtool.sh`. Notifications go to the Telegram contact point `telegram` only when the optional `alerting-secrets` Secret (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) exists; without it the rules still evaluate and show in Grafana (Alerting -> Alert rules). A rule with no data stays OK (`noDataState: OK`); a failing query is an error (`execErrState: Error`). Each Telegram message is a few lines in Vietnamese (template `telegram.vi` in `contact-point.tmpl.yaml`): `🔴 Lỗi` or `✅ Đã ổn`, then per alert its `summary` annotation, `Dịch vụ: <service annotation>` and the start or end time in Asia/Ho_Chi_Minh. Every rule needs both annotations, written with `$labels` (one `$`; `scripts/k8s-validate.sh` enforces this). The `grafana-alerting` ConfigMap has a content-hash name, so a changed rule or template restarts lgtm by itself.

Query the data in Grafana Explore (Prometheus datasource, Loki for logs, Tempo for traces).

## error-rate-high
Severity critical. More than 5% of requests to a service (excluding `/health/*` and the long-running `/tuvi/qa/readings*` routes) answered 5xx over 5m, for 1m, and the service handles more than 0.01 req/s (so an idle service with one failure stays quiet).
- First queries: `sum by (job, http_route, http_response_status_code) (rate(http_server_request_duration_seconds_count{http_response_status_code=~"5.."}[5m]))`; Loki `{service_name="<job>"} |= "ERROR"`; Tempo traces with `status = error` for the service.
- Likely fixes: a bad deploy (check `kubectl -n apps rollout history deploy/<job>` and revert the image tag in `deploy/k8s/overlays/prod/apps/kustomization.yaml`), a down dependency (Postgres, Redpanda, another service), exhausted DB connections.

## latency-p95-high
Severity warning. p95 of `http_server_request_duration_seconds` above 1s for 5m on a service (same route exclusions), only while the service handles more than 0.05 req/s (so one slow request at go-live traffic does not page).
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
Severity critical. A node's filesystem is more than 80% used for 10m (`k8s_node_filesystem_usage_bytes / k8s_node_filesystem_capacity_bytes`, from the collector's kubeletstats receiver, i.e. the kubelet's own filesystem stats; no pod mounts the host root).
- First queries: `max by (k8s_node_name) (k8s_node_filesystem_usage_bytes) / max by (k8s_node_name) (k8s_node_filesystem_capacity_bytes)`; on the node `df -h /` and `du -xh --max-depth=2 / | sort -h | tail`.
- Likely fixes: prune unused images (`k3s crictl rmi --prune`), shrink container logs, check that Prometheus, Loki and Tempo retention is in effect (Prometheus 15d, Loki 30d, Tempo 168h) and the telemetry volume `lgtm-data` is not the culprit, remove old files in `/var/lib/rancher`; if the disk is simply too small, grow it.

## deploy-failed
Severity critical. An Argo CD application has been `Degraded` or `Missing` for 10m (`argocd_app_info{health_status=~"Degraded|Missing"}`, scraped from `argocd-metrics.argocd.svc.cluster.local:8082`).
- First queries: `argocd app get <name>` or the Argo CD UI (which resource is unhealthy), `kubectl -n argocd get applications`, `scripts/verify-deploy.sh --app <name>`.
- Likely fixes: an invalid manifest or missing Secret (for example a sealed secret that was never applied), a failing rollout (see `pod-restart-loop`), a CD commit that should be reverted. No data at all for this rule (for example Argo CD is not installed) is not an alert.

## backup-skipped
Severity warning. The nightly `pg-backup` skipped because S3 is not configured (`ops-secrets` absent, `BACKUP_REQUIRED=0`): there is no off-host copy of the data. `backup.sh` pushes `platform_backup_skipped=1` (job `pg-backup`) to the collector on each skip; the rule looks back 26h, so it keeps firing while the backups keep skipping and clears a day after the first real backup.
- First queries: `max_over_time(platform_backup_skipped[26h])`; `kubectl -n data logs job/<latest pg-backup job> -c upload` (`backup: S3 not configured, skipping`).
- Fix: seal `ops-secrets` and set both `BACKUP_REQUIRED` to `"1"` in one PR (`docs/runbooks/go-live.md` §5; `scripts/k8s-validate.sh` check `prod-backup` keeps the two together), then run a restore drill (§8).

## mail-cap-reached
Severity warning. identity-svc refused magic-link mail at the global daily cap (`MAIL_DAILY_CAP`, default 200 over all addresses per rolling 24h): logins by email answer 429 until older links age out. Counter `identity_magic_link_daily_cap_reached_total`.
- First queries: `sum by (job) (increase(identity_magic_link_daily_cap_reached_total[1h]))`; Loki `{service_name="identity-svc"} |= "daily mail cap reached"`; the Traefik 429s on `/vi/dang-nhap` (edge limit per client IP).
- Likely causes: a bot spraying addresses (keep the cap; consider Cloudflare Turnstile, go-live.md §1), or real growth (raise `MAIL_DAILY_CAP` in `deploy/k8s/overlays/prod/apps/patches/identity-svc.yaml`, within the SMTP provider's daily quota).

## mail-client-cap-hits
Severity warning. identity-svc dropped more than 10 magic-link requests in an hour at the per-client cap (`MagicLinkPolicy.CLIENT_DAILY_MAX`, 20 per client IP per rolling 24h, keyed on an HMAC of the IPv4 address or IPv6 /64 that web sends as `X-Client-Ip`). The caller still got 202, so the user only sees that no mail arrives. Counter `identity_magic_link_client_cap_reached_total` (no client label).
- First queries: `sum by (job) (increase(identity_magic_link_client_cap_reached_total[1h]))`; the Traefik 429s on `/vi/dang-nhap` and on Server Action POSTs.
- Likely causes: one bot spraying addresses (the cap is doing its job; Turnstile on the login form stops it earlier), or many real users behind one carrier NAT or office IP (raise `CLIENT_DAILY_MAX` in a PR, or point them to Google login).

## Collector privileges and residual risks
- The OpenTelemetry collector DaemonSet runs as root only to read the root-owned pod logs under `/var/log/pods` (read-only hostPath, narrowest path). It has no other host mount: a hostPath may only be `/var/log/pods` or `/var/log/containers`, mounted read-only by every container (`scripts/k8s-validate.sh` check `hostpath-root` is an allowlist and fails the build otherwise; so the host root, `/var`, `/etc`, `/var/lib/rancher` and `/etc/rancher` never reach a pod), because a pod with host-root access and a ServiceAccount token could read the k3s token and every Secret.
- It does carry a ServiceAccount token with cluster-wide read-only access to the resources of the `k8s_cluster` receiver plus `nodes/stats` (kubeletstats). It does not read Secrets. Residual risk: a compromised collector (it listens for OTLP inside the cluster) could read pod logs of the `apps` namespace and cluster state. Accepted; to shrink it further, move `k8s_cluster` and `kubeletstats` into a small non-root Deployment and drop the token from the DaemonSet (needed anyway on a multi-node cluster, see ADR 0004).
- The Telegram token is rendered by the init container only into a memory-backed emptyDir (mode 0600) and never into the Grafana container environment. It is rendered only at pod start. In prod the init container waits for `alerting-secrets` (non-optional ref) and the pod template carries `platform.dev/alerting-secrets-rev` (`deploy/k8s/overlays/prod/platform/kustomization.yaml`): bump it in the PR that re-seals the secret and Argo CD restarts lgtm by itself (by hand: `sudo kubectl -n observability rollout restart deployment/lgtm`). Then check the `telegram` contact point (`docs/runbooks/go-live.md` §8). A chat id without a token logs `alerting: WARN ...` in the `alerting-render` init container. Removing `alerting-secrets` later does not delete the contact point or policy Grafana already stored: delete them in Grafana (Alerting -> Contact points / Notification policies) or recreate the pod's Grafana state.
