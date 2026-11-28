# Durable logs, top-up and daily-summary messages on Telegram

- Date: 2026-11-28
- Status: approved by the owner's brief (one PR); choices below are decided, not open
- Plan: `docs/superpowers/plans/2026-11-28-logs-payments-telegram.md`

## 1. Log durability

Today: stdout -> `/var/log/pods/apps_*` -> otel-collector `filelog` (`start_at: end`, no checkpoint) -> OTLP -> Loki in
lgtm (PVC `lgtm-data` 5Gi, Loki 168h).

Audit findings:
- A collector restart loses logs. `start_at: end` with no storage means every line written while the collector was down
  (rollout, OOM, node reboot) is skipped. Lines are also lost when lgtm is down longer than the in-memory retry (5 min),
  and on any collector restart while lines are still queued.
- A pod that crashes keeps its log files (kubelet keeps the previous container's log), so those lines are read. A
  deleted pod's directory is removed by kubelet. The collector polls every 200 ms, so its lines are read before deletion
  unless the collector is down at that moment. That residual risk is accepted (no node agent beyond the collector).

Decisions:
- (a) `file_storage` extension on a hostPath `/var/lib/otelcol` (DirectoryOrCreate, writable, otel-collector only),
  used by `filelog` (`storage`, checkpoints per file) and by the `otlphttp` exporter (persistent `sending_queue`).
  `start_at: beginning`: with checkpoints it only applies to files the collector has never seen, so a file created while
  the collector was down is read from its start. The first rollout re-reads the files still on disk (kubelet keeps about
  10Mi per container): a one-off duplicate, accepted. The k8s-validate `hostpath-root` policy gets one narrow exception:
  path exactly `/var/lib/otelcol`, writable, only in DaemonSet `otel-collector`.
- (b) Retention: Loki 30d (was 168h); Tempo 168h and Prometheus 15d stay. No prod access in this session, so this is an
  estimate. The services log about 1-3 lines per request at ~300-500 B. At the current traffic (well under 10k
  requests/day) that is under ~15 MB/day raw, and Loki compresses about 5-10x, so 30d is under ~100 MB. The PVC (5Gi,
  local-path, not enforced) is dominated by Prometheus; `disk-usage-high` guards the node disk. The daily log export
  prints the exported size, which gives the real volume.
- (c) Off-host copy: CronJob `log-export` (ns data, 00:30 Asia/Ho_Chi_Minh). It exports the previous local day from Loki
  with `logcli` (all streams, jsonl, gzip) and uploads it with rclone to `<bucket>/logs/<YYYY-MM-DD>.jsonl.gz`, kept 90d,
  using the same `ops-secrets` S3 target as pg-backup. Without S3 it logs `log-export: S3 not configured, skipping` and
  exits 0, and fails instead when `BACKUP_REQUIRED=1`, exactly like pg-backup.
- (d) Alert `log-pipeline-down`: no log record exported by the collector for 15m. The metric is
  `otelcol_exporter_sent_log_records_total` from the collector's own telemetry, scraped by its prometheus receiver.
  Idle apps can be silent at night, so the heartbeat CronJob (every 5 min, ns data) now prints a line each run and its
  pods are included in `filelog`: a healthy pipeline always exports something. The expr uses `or vector(0)`, so a
  missing metric (collector down) fires too.

## 2. Telegram on every top-up

notify-svc (the notification service, Rust) consumes `payments.payment-succeeded.v1`: a second consumer, group
`notify-svc-payments`, idempotent through `platform.processed_event`.

- In one transaction it stores a `payments` row (event_id, product_code, amount, currency, paid_at) and an outbox row
  `telegram_outbox(key = "payment:<event_id>", text)`. A sender loop delivers pending outbox rows to the Telegram Bot
  API and marks them sent, retrying with backoff. Kafka never waits for Telegram, and a Telegram outage delays the
  message instead of losing it.
- Message: `💰 Nạp tiền` / `Gói: CREDIT_5 (5 lượt)` / `Số tiền: 50.000đ` / `Lúc: HH:MM dd/MM` (Asia/Ho_Chi_Minh,
  from `paid_at`). USD is shown as `5.00 USD`. No email, phone, name, account number, account id or order id.
- Replay guard: a new consumer group reads the topic from the start. An event whose `paid_at` is more than 1h old is
  stored (it counts in revenue) but not announced, so the first deploy does not replay old top-ups to the phone.
- Secret: optional `notify-telegram-secrets` (ns apps: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`), a sealed copy of the
  alerting bot and chat. Without it the outbox still fills and nothing is sent (logged once at startup). The token is
  only in the request URL, never logged: error messages are built without the URL.

## 3. Daily summary 23:59

Also in notify-svc, which owns the revenue data (the `payments` projection) and the outbox. Choice: an in-process
scheduler instead of a CronJob, so there is no extra image, endpoint or auth.

- Every 30s the scheduler computes the target day: the latest day whose 23:59 (Asia/Ho_Chi_Minh) has passed. If the
  outbox has no `summary:<day>` row, it builds the summary and inserts it. The unique key gives one message per day
  across restarts, retries and replicas. A pod that was down at 23:59 sends it when it comes back (same day key).
- Content: revenue per package (count, total) from `payments`; uses for the day (basic readings, Q&A, xem số) as
  `increase` of the reading-tuvi 2xx POST route counters from Prometheus; 5xx % per service from Prometheus (only
  services above 0); alerts fired = Grafana alert state-history annotations with new state Alerting. Prometheus queries
  are evaluated at the day's end, so a late send still covers that day.
- If Prometheus or Grafana does not answer, the scheduler retries until 30 min after the day ends, then sends with
  `không lấy được` for that line. Revenue comes from notify-svc's own DB and is always exact.

## Out of scope (follow-ups)
- Grafana in lgtm runs anonymous Admin inside the cluster (image default): any pod can call its API. Pre-existing.
- R2 credentials (owner): until then pg-backup and log-export both skip.
