# Plan: durable logs, top-up and daily-summary Telegram

Spec: `docs/superpowers/specs/2026-11-28-logs-payments-telegram-design.md`. One PR.

## A. Logs (deploy/k8s, scripts)
1. Red: k8s-validate self-test fixture: a writable hostPath `/var/lib/otelcol` in a non-collector workload is still
   rejected. Green: narrow `hostpath-root` exception for DaemonSet `otel-collector`.
2. otel-collector: `file_storage` extension + hostPath volume, `filelog.storage`, `start_at: beginning`, include
   heartbeat pod logs, exporter `sending_queue.storage`, self-telemetry scrape job `otelcol`.
3. heartbeat.sh prints one line per run (ok or skipping).
4. lgtm: Loki `-store.retention=720h`; e2e-observability expects 30d.
5. Red: promtool tests for `log-pipeline-down` (fires on no exports, fires on missing metric, silent while exporting).
   Green: rule + Vietnamese summary/service, metric-names.txt, ALERT_UIDS, runbook section.
6. `log-export.sh` + CronJob (logcli export -> rclone upload, skip/require like backup.sh) with a shell test in
   `scripts/test/` (skip path, require path, dates).

## B. notify-svc top-up messages
1. Domain (red first): `TopUp` from PaymentSucceeded data, VND formatting `50.000đ`, package labels, message text,
   VN-time formatting; stale check (1h).
2. Migration `0002`: `payments`, `telegram_outbox`.
3. Application: `PaymentService::record` (payment + outbox in the tx, stale = no outbox) with in-memory fakes.
4. Adapters: `PaymentSucceededHandler` (parse, malformed handling), Postgres repo, Telegram client (no token in
   errors), outbox sender loop.
5. Wiring: second consumer, sender task; config `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` (both or neither),
   `PROMETHEUS_URL`, `GRAFANA_URL`.
6. Contract test against `payment-succeeded.v1.schema.json`; schema description lists notify-svc.

## C. Daily summary
1. Domain: target-day rule (latest 23:59 passed), summary text (revenue lines, uses, 5xx, alerts, `không lấy được`).
2. Application: `SummaryService::tick` with fakes (already sent -> nothing; metrics fail before deadline -> retry;
   after deadline -> partial).
3. Adapters: Prometheus instant query client, Grafana annotations client, repo revenue-by-day query.

## D. Deploy and docs
- notify-svc deployment env + optional `notify-telegram-secrets` ref; secrets.tsv row; sealed copy (prod).
- docs/system (flows, secrets, alerts, history line), runbooks (alerts, logs), CLAUDE.md untouched.
- Verify: `scripts/cargo.sh fmt --check`, `clippy`, `test -p notify-svc`, k8s-validate (+ self-test), promtool,
  `scripts/test/*.test.sh` touched, check-system-docs.
