# Plan 3 — Kustomize manifests, observability stack, kind e2e in CI, RED metrics

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every service (order-svc, notify-svc, chart-svc, reading-tuvi, identity-svc, payment-svc, web) plus Postgres, Redpanda and the observability stack is described in Kustomize (one base per service, `platform/`, a prod overlay placeholder and an e2e overlay). Java and Rust services emit the same RED metrics (HTTP and Kafka consumer) over OTLP, and Kafka consumer lag is measured. Each run of the CI job `k8s-e2e` deploys the full stack into a throwaway kind cluster and runs the existing smoke scripts plus a trace/metrics/logs check. The dev machine never runs kind.

**Architecture:** Services push OTLP (traces + metrics) to an in-cluster OpenTelemetry Collector. A DaemonSet collector also tails pod logs (JSON) and scrapes Redpanda's consumer-lag metrics, then forwards everything to one `grafana/otel-lgtm` pod (Prometheus + Loki + Tempo + Grafana), which is the same image the local compose uses. Metric names follow OTel semantic conventions and are produced the same way in both languages. Java gets HTTP metrics from the OTel Java agent it already runs, and platform-starter adds consumer and outbox metrics through the OTel API. Rust gets HTTP and consumer metrics from platform-core through the OTel metrics SDK. Grafana dashboards are JSON in Git, mounted by both k8s and compose.

**Tech Stack:** Kustomize (built into `kubectl`), kind, kubeconform, yq/jq, `alpine/k8s` image for local validation, OpenTelemetry Collector contrib, `grafana/otel-lgtm:0.41.1`, Postgres 17, Redpanda v26.1.1, OTel Java API + `opentelemetry-sdk-testing`, Rust `opentelemetry_sdk` metrics + `opentelemetry-otlp` `metrics` feature, GitHub Actions. Versions: latest stable at implementation time, pinned by exact version (actions and kind binary by SHA/checksum).

**Spec:** `docs/superpowers/specs/2026-11-23-platform-foundation-design.md` (§5.1, §5.4, §6, §7.5, §10, §11, §13, §14 item 4, §16.3). Prior plans: `docs/superpowers/plans/2026-11-24-plan-2-rust-platform-core-and-notify-svc.md`. Out of scope (Plan 4): host bootstrap, Argo CD, Sealed Secrets controller and real SealedSecrets, cloudflared/ingress host, alerts, backup, retention tuning, CD tag bump.

## Global Constraints

- Kustomize only, no Helm. Layout: `deploy/k8s/base/<svc>/`, `deploy/k8s/platform/`, `deploy/k8s/overlays/{prod,e2e}/`. Dashboards in `deploy/k8s/platform/grafana/dashboards/` (spec §10).
- Namespaces: `apps` (7 services), `data` (postgres, redpanda, and mailpit in e2e only), `observability` (otel-collector, lgtm).
- Every HTTP container has liveness `GET /health/live` and readiness `GET /health/ready`. Java containers also have a startupProbe on `/health/live` with `periodSeconds: 5, failureThreshold: 36` (180 s). Mailpit uses `/livez` and `/readyz`.
- Memory `requests == limits` = compose `mem_limit`: postgres 256Mi, redpanda 768Mi, lgtm 1024Mi, order-svc 768Mi, notify-svc 128Mi, chart-svc 64Mi, reading-tuvi 384Mi, identity-svc 512Mi, payment-svc 512Mi, web 256Mi, mailpit 64Mi; otel-collector 256Mi (new). CPU requests only, no CPU limits: Java 100m, Rust 25m, web 50m, postgres 100m, redpanda 250m, lgtm 200m, collector 50m, mailpit 10m.
- `JAVA_TOOL_OPTIONS` copied from compose (order-svc `MaxRAMPercentage=60`, reading-tuvi `50`); identity/payment keep the Dockerfile value (`50`).
- Pod security for apps: `runAsNonRoot: true` with a numeric `runAsUser` (Java 10001, Rust 65532, web 1000, because the web image's `USER node` is non-numeric and kubelet rejects it otherwise), `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `readOnlyRootFilesystem: true` with an `emptyDir` at `/tmp` (web: `false`, since Next.js writes `.next/cache`). The collector DaemonSet is the only root pod, because it reads hostPath `/var/log/pods` read-only. This is documented in the ADR.
- Secrets: no `kind: Secret` in `overlays/prod` output and no SealedSecret yet. Each app reads secrets via `envFrom: [{secretRef: {name: <svc>-secrets}}]`. Postgres reads `postgres-secrets`. The e2e overlay generates these with `secretGenerator` (`disableNameSuffixHash: true`) from `deploy/k8s/overlays/e2e/secrets/<name>.env`, which hold the **same test values as compose defaults / `deploy/local/.env.example`** and nothing else.
- Base is prod-safe: `DEPLOY_ENV=prod`, `DEV_PAYMENT_SIMULATOR=0`, `COOKIE_SECURE=1`, `PAYPAL_MODE=fake`, `SEPAY_RECONCILE_ENABLED=false`. The e2e overlay flips these to compose values.
- Images in base: `ghcr.io/nguyentuan2686/<svc>`. Prod overlay `images:` sets `newTag: main` (CD bumps to `sha-<commit>` in Plan 4). E2E overlay sets `newName: <svc>`, `newTag: e2e` and `imagePullPolicy: IfNotPresent`.
- In-cluster addresses: Postgres `postgres.data.svc.cluster.local:5432`, Kafka `redpanda.data.svc.cluster.local:9092`, OTLP `http://otel-collector.observability.svc.cluster.local:4318` (`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`), service-to-service `http://<svc>.apps.svc.cluster.local:<port>` (ports as compose: 8080–8085, web 3000).
- Metrics transport: OTLP push only, no `/prometheus` scraping of services. Prometheus names after OTLP translation, with service in label `job`:
  - `http_server_request_duration_seconds_{bucket,count,sum}`, labels `http_request_method`, `http_route` (template, or absent when unmatched), `http_response_status_code`. Buckets (s): `0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10`.
  - `messaging_process_duration_seconds_{bucket,count,sum}`, labels `messaging_destination_name` (topic), `messaging_consumer_group_name`, `outcome` ∈ `processed|duplicate|malformed|ignored|failed`. Same buckets.
  - `platform_outbox_lag_seconds` (gauge, age of the oldest unpublished outbox row, 0 when none), Java services with outbox.
  - `redpanda_kafka_consumer_group_lag_max` / `_sum`, label `redpanda_group` (scraped from Redpanda, language-neutral).
- `OTEL_METRIC_EXPORT_INTERVAL`: 60000 (default) in base, `10000` in e2e.
- Dev machine (2 cores / 12 GB) never runs kind or the k8s stack. Local check is only `scripts/k8s-validate.sh` in Docker. Heavy verification runs in CI (`ubuntu-24.04`, 4 vCPU / 16 GB).
- Tests wait on observable state with a deadline, never a fixed sleep (CLAUDE.md Testing).

## Review Focus

1. **Route cardinality.** A request to an unmatched path (`/nope/123`) or a path with ids (`/items/42`) must record `http.route` as the template or leave it absent, never the raw path, or Prometheus series explode. Test in Task 1 (`unmatched_route_has_no_route_attribute`, `templated_route_recorded`).
2. **Telemetry backend down.** With the collector or lgtm unreachable, services must still start and serve: exporter errors are logged, nothing panics, and shutdown is not blocked. Test in Task 1 (`tests/telemetry_unreachable.rs`) and Task 2 (agent default behaviour, asserted by the IT running with no collector).
3. **Consumer failure paths are counted.** A Java listener that throws counts `outcome=failed` once per attempt and never `processed`. Rust DB-down retries count `failed` and then exactly one `processed`. Test in Task 1 (`db_down_retries_until_db_back` extended) and Task 2 (`ConsumerMetricsIT.failing_listener_counts_failed_per_attempt`).
4. **Slow dependencies at startup on a 4-vCPU runner.** Postgres and Redpanda are slow and Java services take a long time to boot. Liveness must not kill a booting pod, and the e2e must wait on readiness with a deadline. Test in Task 3/4 (`k8s-validate.sh` asserts the startupProbe budget ≥ 180 s for Java) and Task 6 (`kubectl wait` with timeouts, no sleeps).
5. **Secret wiring drift.** A typo in a secret name or key must fail in CI, not show up later as `CreateContainerConfigError`, and prod must never contain a plaintext Secret. Test in Task 4 (`k8s-validate.sh`: every `envFrom.secretRef` in the e2e build resolves to a Secret in the same build; prod build has zero `kind: Secret`).

---

### Task 1: Rust RED metrics in platform-core (HTTP + consumer), wired into notify-svc and chart-svc

**Files:**
- Modify: `Cargo.toml` (`opentelemetry-otlp` features add `"metrics"`), `libs/rust/platform-core/src/telemetry/mod.rs` (meter provider), `libs/rust/platform-core/src/http/mod.rs`, `libs/rust/platform-core/src/messaging/consumer.rs`, `services/notify-svc/src/adapters/http.rs`, `services/chart-svc/src/adapters/http.rs`
- Create: `libs/rust/platform-core/src/http/metrics.rs`, `libs/rust/platform-core/src/messaging/metrics.rs`
- Test: `libs/rust/platform-core/tests/http_metrics.rs`, `libs/rust/platform-core/tests/telemetry_unreachable.rs`, `libs/rust/platform-core/tests/consumer_it.rs` (extend)

**Interfaces:**
- Produces:
  - `telemetry::init` additionally builds an `SdkMeterProvider` (resource `service.name`). When `OTEL_EXPORTER_OTLP_ENDPOINT` is non-empty it gets a `PeriodicReader` over an OTLP http/protobuf `MetricExporter` (cumulative temporality; honours `OTEL_METRIC_EXPORT_INTERVAL`). It is installed with `opentelemetry::global::set_meter_provider`. `TelemetryGuard` also shuts the meter provider down on drop.
  - `pub const DURATION_BUCKETS_S: [f64; 14]`: the bucket list from Global Constraints.
  - `pub fn http_metrics_layer() -> HttpMetricsLayer` (uses `global::meter("platform-core")`) and `pub fn http_metrics_layer_with_meter(meter: &opentelemetry::metrics::Meter) -> HttpMetricsLayer`. It is a tower `Layer` that records f64 histogram `http.server.request.duration` (unit `s`, boundaries `DURATION_BUCKETS_S`) with attributes `http.request.method`, `http.response.status_code` (i64), and `http.route` only when `MatchedPath` exists. Apply with `Router::layer` next to `trace_layer()`.
  - `messaging::metrics::ConsumerMetrics` (crate-private constructor `from_meter(&Meter)`, `global()`). Records `messaging.process.duration` (unit `s`, same boundaries) with attributes `messaging.destination.name`, `messaging.consumer.group.name`, `outcome`. `run_consumer` records once per handled message: `processed`/`duplicate` from `Outcome`, `malformed` in `skip_malformed`, `ignored` for other event types, and `failed` once per failed attempt (before the backoff sleep). The `run_consumer` signature is unchanged.

- [ ] **Step 1: Write failing tests**
  - `tests/http_metrics.rs`, with a `SdkMeterProvider` over `InMemoryMetricExporter` and a router with `GET /items/{id}` (200), `GET /boom` (returns `ApiError::Internal`) and the layer from `http_metrics_layer_with_meter`. After `provider.force_flush()`:
    - `templated_route_recorded`: one data point with `http.route == "/items/{id}"`, method `GET`, status `200`, count 1.
    - `unmatched_route_has_no_route_attribute`: `GET /nope/123` → status `404`, and no data point carries an `http.route` attribute (Review Focus 1).
    - `server_error_status_recorded`: `/boom` → status `500`.
    - `histogram_uses_platform_buckets`: bounds == `DURATION_BUCKETS_S`.
  - `tests/telemetry_unreachable.rs` (own binary, sets `OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:9` before `init`): `init` returns `Ok`; record one histogram value via `global::meter`; dropping the guard finishes within 10 s (spawn the drop on a thread and wait on a channel with a timeout) (Review Focus 2).
  - `consumer_it.rs`: install a global in-memory meter provider once (`OnceLock`). Filter points by this test's topic.
    - `consumer_metrics_record_outcomes`: valid, duplicate, non-JSON and other-type messages → counts `processed=1, duplicate=1, malformed=1, ignored=1`.
    - Extend `db_down_retries_until_db_back`: `failed >= 1` and `processed == 1` (Review Focus 3).
  - All waits use a deadline loop on the exporter's contents.
- [ ] **Step 2: Run `scripts/cargo.sh test -p platform-core`** → FAIL (unresolved `http_metrics_layer_with_meter`, missing metrics).
- [ ] **Step 3: Implement** the meter provider in `telemetry::init`, `http/metrics.rs`, `messaging/metrics.rs`, and the recording calls in `consumer.rs`. Re-export `http_metrics_layer`, `http_metrics_layer_with_meter` and `DURATION_BUCKETS_S` from `platform_core::http`. Add `.layer(http_metrics_layer())` in notify-svc and chart-svc routers, next to `.layer(trace_layer())`.
- [ ] **Step 4: Run** `scripts/cargo.sh test --workspace`, `scripts/cargo.sh fmt --check`, `scripts/cargo.sh clippy --all-targets -- -D warnings`, `scripts/cargo.sh deny check` → all PASS; workspace coverage still ≥ 80 % (CI `_rust-service.yml` gate).
- [ ] **Step 5: Commit** `feat(platform-core): OTLP RED metrics for HTTP and Kafka consumer`

---

### Task 2: Java consumer and outbox metrics in platform-starter (OTel API)

Java HTTP RED already comes from the OTel Java agent (`http.server.request.duration`, `OTEL_METRICS_EXPORTER=otlp` in every Java Dockerfile). This task adds no HTTP code. It adds the two metrics the agent does not produce, with the same names and labels as Rust.

**Files:**
- Create: `libs/java/platform-starter/src/main/java/dev/platform/starter/messaging/ConsumerMetricsInterceptor.java`, `.../messaging/MessagingMetricsAutoConfiguration.java`, `.../outbox/OutboxLagGauge.java`, `.../telemetry/PlatformMeter.java`
- Modify: `.../outbox/OutboxAutoConfiguration.java` (register `OutboxLagGauge` bean), `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `libs/java/platform-starter/pom.xml` (`opentelemetry-sdk-testing` test scope; `opentelemetry-sdk` test scope)
- Test: `libs/java/platform-starter/src/test/java/dev/platform/starter/messaging/ConsumerMetricsInterceptorTest.java`, `.../messaging/ConsumerMetricsIT.java`, `.../outbox/OutboxLagGaugeIT.java`

**Interfaces:**
- Produces:
  - `PlatformMeter.of(ObjectProvider<OpenTelemetry> otel) -> io.opentelemetry.api.metrics.Meter`: the `OpenTelemetry` bean if one exists (tests), else `GlobalOpenTelemetry.get()` (agent in prod). Instrumentation scope `dev.platform.starter`. `PlatformMeter.DURATION_BUCKETS_S = List.of(0.005, …, 10.0)` (same list as Rust).
  - `ConsumerMetricsInterceptor implements RecordInterceptor<Object,Object>`: `intercept` stores `System.nanoTime()` in a ThreadLocal; `success` records `outcome=processed`, `failure` records `outcome=failed`; `afterRecord` clears the ThreadLocal. Attributes: `messaging.destination.name = record.topic()`, `messaging.consumer.group.name = consumer.groupMetadata().groupId()`. Histogram `messaging.process.duration`, unit `s`, explicit bucket advice `DURATION_BUCKETS_S`.
  - `MessagingMetricsAutoConfiguration`: `@ConditionalOnClass(RecordInterceptor.class)`, bean `@ConditionalOnMissingBean(RecordInterceptor.class)`. Spring Boot's Kafka auto-config applies a single `RecordInterceptor` bean to its listener container factory.
  - `OutboxLagGauge`: observable double gauge `platform.outbox.lag` (unit `s`) whose callback runs `select coalesce(extract(epoch from now() - min(occurred_at)), 0) from outbox_event where published_at is null`. A query failure is logged at WARN and skips the observation; it never throws.

- [ ] **Step 1: Write failing tests**
  - `ConsumerMetricsInterceptorTest` (SDK `SdkMeterProvider` + `InMemoryMetricReader`): `success_records_processed` (one point, the three attributes, count 1); `failure_records_failed`; `thread_local_cleared_after_record` (a second `success` without `intercept` records nothing).
  - `ConsumerMetricsIT` (Testcontainers Redpanda, minimal `@SpringBootApplication` test config with one `@KafkaListener` that throws on payload `"boom"`, a `DefaultErrorHandler(new FixedBackOff(0, 1))` bean, and an `OpenTelemetry` bean built from `OpenTelemetrySdk` + `InMemoryMetricReader`):
    - `listener_success_counts_processed`: send `"ok"` → `processed` count 1 (Awaitility, deadline 30 s).
    - `failing_listener_counts_failed_per_attempt`: send `"boom"` → `failed` count 2, `processed` 0 (Review Focus 3).
    - Runs with no collector at all; startup must not fail (Review Focus 2).
  - `OutboxLagGaugeIT` (existing Postgres Testcontainers setup for outbox ITs): `lag_is_zero_without_pending_rows`; `lag_reflects_oldest_unpublished_row` (insert row `occurred_at = now() - interval '30 seconds'` → gauge ≥ 30 and < 60); `published_rows_do_not_count`.
- [ ] **Step 2: Run** `mvn -q -pl libs/java/platform-starter verify` → FAIL (classes missing).
- [ ] **Step 3: Implement** the four classes and register `MessagingMetricsAutoConfiguration` in the imports file.
- [ ] **Step 4: Run** `mvn -q verify` (whole reactor: all four Java services still green, identity-svc `PaymentSucceededListenerIT` included) → PASS; starter coverage gate holds.
- [ ] **Step 5: Commit** `feat(platform-starter): consumer and outbox lag metrics via OTel API`

---

### Task 3: Platform manifests (namespaces, Postgres, Redpanda, collector, lgtm), validation script and `k8s-manifests` CI job

**Files:**
- Create: `deploy/k8s/platform/kustomization.yaml`, `deploy/k8s/platform/namespaces.yaml`
- Create: `deploy/k8s/platform/postgres/{kustomization.yaml,statefulset.yaml,service.yaml,init-db.sh}`
- Create: `deploy/k8s/platform/redpanda/{kustomization.yaml,statefulset.yaml,service.yaml,bootstrap.yaml}`
- Create: `deploy/k8s/platform/observability/{kustomization.yaml,lgtm.yaml,otel-collector.yaml,otel-collector-config.yaml,rbac.yaml}`
- Create: `scripts/k8s-validate.sh`, `docs/adr/0004-k8s-manifests-and-observability.md`
- Modify: `.github/workflows/ci.yml` (filter `k8s`, job `k8s-manifests`, add to `ci-gate.needs`)

**Interfaces:**
- Produces:
  - Postgres: StatefulSet `postgres` (ns `data`, `postgres:17-alpine`), PVC template `pgdata` 2Gi (default StorageClass), Service `postgres:5432`. `envFrom: postgres-secrets`, keys `POSTGRES_PASSWORD, ORDERS_DB_PASSWORD, NOTIFY_DB_PASSWORD, READING_DB_PASSWORD, IDENTITY_DB_PASSWORD, PAYMENT_DB_PASSWORD`. `init-db.sh` (ConfigMap `postgres-init` → `/docker-entrypoint-initdb.d/`) creates the same users and databases as `deploy/local/init-db.sql` (orders, notify, reading/reading_tuvi, identity, payment) with passwords from those env vars. Readiness `pg_isready -U postgres`.
  - Redpanda: StatefulSet `redpanda` (ns `data`) with the same `redpanda start` flags as compose, except `--kafka-addr=internal://0.0.0.0:9092` and `--advertise-kafka-addr=internal://redpanda.data.svc.cluster.local:9092`. PVC 2Gi. Service ports 9092 (kafka) and 9644 (admin/metrics). `bootstrap.yaml` mounted at `/etc/redpanda/.bootstrap.yaml` (subPath) sets `enable_consumer_group_metrics: [group, partition, consumer_lag]` and `consumer_group_lag_collection_interval_sec: 10`. Readiness `rpk cluster health | grep -q 'Healthy:.*true'`.
  - Collector: DaemonSet `otel-collector` (ns `observability`, `otel/opentelemetry-collector-contrib`, pinned), Service `otel-collector` ports 4317/4318. Pipelines:
    - traces/metrics: `otlp` receiver → `memory_limiter`, `resource` (`deployment.environment.name` from env `DEPLOY_ENV`), `batch` → `otlphttp` to `http://lgtm.observability.svc.cluster.local:4318`.
    - metrics also: `prometheus` receiver scraping `redpanda.data.svc.cluster.local:9644/public_metrics` every 15 s (`job_name: redpanda`).
    - logs: `filelog` on `/var/log/pods/apps_*/*/*.log` → `container` operator → `json_parser` (if body starts with `{`, on_error send) → severity from `attributes.level`, trace from `attributes.trace_id`/`span_id`, body from `attributes.message`, resource `service.name` from `attributes.service` → lgtm.
    - Root, hostPath `/var/log/pods` read-only.
  - lgtm: Deployment `lgtm` (ns `observability`, `grafana/otel-lgtm:0.41.1`), PVC `lgtm-data` 5Gi at the image's data dir (implementer confirms with `docker run --rm --entrypoint sh grafana/otel-lgtm:0.41.1 -c 'ls / /otel-lgtm'`). Service `lgtm` ports 3000 (grafana), 4317, 4318, 9090 (prometheus), 3100 (loki), 3200 (tempo). Readiness `GET :3000/api/health`.
  - `scripts/k8s-validate.sh [overlay...]` (default: `deploy/k8s/platform` and every dir under `deploy/k8s/overlays/`). It runs `kubectl kustomize` and pipes the output into `kubeconform -strict -summary -kubernetes-version <kind node version> -ignore-missing-schemas`, then runs policy checks over the output with `yq -o=json | jq`. The script exits non-zero and prints `POLICY: <check> <kind>/<name>: <reason>` for each failure. Checks: `resources` (every container has `requests.memory == limits.memory` and `requests.cpu`, and no `limits.cpu`); `probes` (every container in ns `apps` has liveness `/health/live` and readiness `/health/ready`); `startup` (every container whose image name ends in `order-svc|reading-tuvi|identity-svc|payment-svc` has startupProbe `periodSeconds*failureThreshold >= 180`); `nonroot` (pods in `apps` have `runAsNonRoot: true` and a numeric `runAsUser`); `no-latest` (no image tag `latest` or missing tag). Tasks 4 and 5 add the overlay and dashboard checks. Local run: `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:<pinned> bash scripts/k8s-validate.sh`.
  - CI job `k8s-manifests` (ubuntu-24.04): installs pinned kubeconform (checksum verified), runs `scripts/k8s-validate.sh`, then Trivy `config` scan on `deploy/k8s` with severity CRITICAL, exit-code 1, using the SHA-pinned trivy action already in use. It runs when `changes.outputs.k8s == 'true' || root == 'true'`. Filter `k8s`: `deploy/k8s/**`, `deploy/local/**`, `scripts/k8s-*.sh`, `scripts/e2e-*.sh`, `scripts/smoke-*.sh`, `.github/workflows/_k8s-e2e.yml`.

- [ ] **Step 1: Write the validation script first**, with the policy checks above and self-test fixtures `scripts/testdata/k8s-validate/{bad-resources,bad-probes}/kustomization.yaml`. Each fixture has one Deployment that violates one check. Add a `--self-test` flag that asserts each fixture fails with its expected `POLICY: <check>` line.
- [ ] **Step 2: Run** `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:<pinned> bash scripts/k8s-validate.sh --self-test` → PASS (fixtures rejected). Then run `bash scripts/k8s-validate.sh deploy/k8s/platform` → FAIL (path does not exist yet).
- [ ] **Step 3: Write the platform manifests** and the ADR 0004. The ADR records: OTLP push; otel-lgtm single pod now, split or retention tuning in Plan 4; lag measured by Redpanda; collector runs as root for hostPath; base is prod-safe and secrets arrive by name; e2e runs the full stack.
- [ ] **Step 4: Run** the validator on `deploy/k8s/platform` → PASS (kubeconform `Invalid: 0, Errors: 0`, no POLICY lines).
- [ ] **Step 5: Add the `k8s-manifests` job and filter to `ci.yml`**, plus `k8s-manifests` in `ci-gate.needs`. Validate the YAML with `docker run --rm -v "$PWD:/w" -w /w rhysd/actionlint:<pinned>`, which must print no errors.
- [ ] **Step 6: Commit** `feat(k8s): platform manifests, manifest validation and k8s-manifests CI job`

---

### Task 4: Service bases and overlays (prod placeholder, e2e)

**Files:**
- Create: `deploy/k8s/base/<svc>/{kustomization.yaml,deployment.yaml,service.yaml}` for `order-svc, notify-svc, chart-svc, reading-tuvi, identity-svc, payment-svc, web`
- Create: `deploy/k8s/overlays/prod/{kustomization.yaml,ingress.yaml,patches/*.yaml}`
- Create: `deploy/k8s/overlays/e2e/{kustomization.yaml,mailpit.yaml,patches/*.yaml,secrets/*.env}`
- Modify: `scripts/k8s-validate.sh` (overlay checks), `deploy/local/.env.example` (comment pointing to `overlays/e2e/secrets/` as the same test values)

**Interfaces:**
- Consumes: Task 3 namespaces, `postgres-secrets`, collector Service, validator.
- Produces:
  - Per service: Deployment `<svc>` (1 replica, ns `apps`, labels `app.kubernetes.io/name: <svc>`, `app.kubernetes.io/part-of: platform`), Service `<svc>` (ClusterIP, compose port; web exposes 3000). Env is compose `environment` with hostnames rewritten per Global Constraints, `OTEL_EXPORTER_OTLP_ENDPOINT` pointing at the collector, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, and `OTEL_SERVICE_NAME=<svc>` for Rust. Secret keys move to `<svc>-secrets`:

    | Secret | Keys |
    |---|---|
    | `order-svc-secrets` | `DB_USER, DB_PASSWORD` |
    | `notify-svc-secrets` | `DB_URL` |
    | `reading-tuvi-secrets` | `DB_USER, DB_PASSWORD, INTERNAL_API_TOKEN, QA_ENC_KEY, GROQ_API_KEY, GEMINI_API_KEY` |
    | `identity-svc-secrets` | `DB_USER, DB_PASSWORD, INTERNAL_API_TOKEN, PROFILE_ENC_KEY, OIDC_GOOGLE_CLIENT_SECRET` |
    | `payment-svc-secrets` | `DB_USER, DB_PASSWORD, INTERNAL_API_TOKEN, ADMIN_API_TOKEN, SEPAY_WEBHOOK_SECRET, PAYPAL_FAKE_WEBHOOK_SECRET` |
    | `web-secrets` | `INTERNAL_API_TOKEN, ADMIN_API_TOKEN, SEPAY_WEBHOOK_SECRET` |

  - Env-specific values live only in overlay patches. Prod: `LLM_PROVIDERS` per ADR 0003, `MAIL_HOST/PORT/FROM` placeholders (`smtp.example.invalid`), `WEB_BASE_URL`/`WEB_ORIGIN` `https://app.example.invalid`, `DEPLOY_ENV=prod`. E2E: compose values, with `MAIL_HOST=mailpit.data.svc.cluster.local`, `LLM_PROVIDERS=fake`, `LLM_FAKE_MODE=ok`, `DEV_PAYMENT_SIMULATOR=1`, `DEPLOY_ENV=e2e`, `COOKIE_SECURE=0`, `WEB_BASE_URL=http://localhost:3001`, `OTEL_METRIC_EXPORT_INTERVAL=10000` on all apps, and the collector's `DEPLOY_ENV=e2e`.
  - `overlays/prod/kustomization.yaml`: resources `../../platform` + 7 bases + `ingress.yaml`. The Ingress is `web` with `ingressClassName: traefik` and host `app.example.invalid`; a comment says Plan 4 sets `APP_DOMAIN`. It also sets `images:` `newTag: main`. A header comment lists the Secrets Plan 4 must supply as SealedSecrets (the table above + `postgres-secrets`). No Secret objects.
  - `overlays/e2e/kustomization.yaml`: resources `../../platform` + 7 bases + `mailpit.yaml` (Deployment + Service `mailpit` in ns `data`, `axllent/mailpit:v1.21.8`, ports 1025/8025, probes `/livez` `/readyz`). It has `secretGenerator` entries for all 7 secrets (6 apps + `postgres-secrets`) with `envs: [secrets/<name>.env]`, `generatorOptions.disableNameSuffixHash: true`, `images:` `newName: <svc>, newTag: e2e`, and a JSON6902 patch setting `imagePullPolicy: IfNotPresent` on every app container.
  - Validator additions: `prod-no-secrets` (zero `kind: Secret` in the `overlays/prod` build); `secret-refs` (every `envFrom[].secretRef.name` in the `overlays/e2e` build names a Secret present in that build, and every `secretKeyRef` key exists in it); `prod-images` (every app image in prod matches `^ghcr.io/nguyentuan2686/<svc>:`).

- [ ] **Step 1: Add the three overlay checks** to `k8s-validate.sh`, plus fixtures `scripts/testdata/k8s-validate/{prod-with-secret,dangling-secret-ref}/` and entries in `--self-test` (Review Focus 5).
- [ ] **Step 2: Run** `--self-test` → PASS, then `bash scripts/k8s-validate.sh deploy/k8s/overlays/prod deploy/k8s/overlays/e2e` → FAIL (overlays do not exist).
- [ ] **Step 3: Write the 7 bases and both overlays.** Diff each base's env against `deploy/local/docker-compose.yml` by hand. Every compose variable must be accounted for: base, `<svc>-secrets`, or an overlay patch.
- [ ] **Step 4: Run** the validator on all paths → PASS (kubeconform 0 invalid, no POLICY lines; `startup` check satisfied for the 4 Java services, Review Focus 4).
- [ ] **Step 5: Commit** `feat(k8s): service bases, prod placeholder overlay and e2e overlay`

---

### Task 5: Grafana dashboards as code (k8s + compose)

**Files:**
- Create: `deploy/k8s/platform/grafana/{kustomization.yaml,provisioning/platform-dashboards.yaml,metric-names.txt}`, `deploy/k8s/platform/grafana/dashboards/{platform-red.json,platform-messaging.json}`
- Modify: `deploy/k8s/platform/observability/{kustomization.yaml,lgtm.yaml}` (mount ConfigMaps), `deploy/local/docker-compose.yml` (`lgtm` mounts the same two paths read-only), `scripts/k8s-validate.sh` (dashboard check)

**Interfaces:**
- Consumes: metric names and labels from Global Constraints (produced by Tasks 1–3).
- Produces:
  - `configMapGenerator` `grafana-dashboards` (both JSON files) and `grafana-dashboard-provider` (`platform-dashboards.yaml`: file provider, folder `Platform`, path `/var/lib/grafana/dashboards/platform`), with `disableNameSuffixHash: true`. The lgtm pod mounts the provider file into the image's Grafana provisioning `dashboards/` dir (implementer confirms the path in the image, as in Task 3) and the JSONs at the provider path. Compose mounts the same files at the same paths.
  - Datasources by the uids otel-lgtm provisions (confirm from the image's provisioning `datasources/` file; expected `prometheus`, `loki`, `tempo`).
  - `platform-red.json` (uid `platform-red`, title `Platform / RED`). Variable `service` = `label_values(http_server_request_duration_seconds_count, job)`, multi, include All. Panels (filter `job=~"$service", http_route!~"/health/.*"`):
    - Rate: `sum by (job) (rate(http_server_request_duration_seconds_count{…}[5m]))`.
    - Error %: 5xx rate / total rate by `job`.
    - p95: `histogram_quantile(0.95, sum by (job, le) (rate(http_server_request_duration_seconds_bucket{…}[5m])))`.
    - Table: top routes by rate, with columns `job, http_route, http_request_method`.
  - `platform-messaging.json` (uid `platform-messaging`, title `Platform / Messaging`). Panels:
    - Consumer rate by `job, messaging_destination_name, outcome`.
    - Consumer failure % (`outcome="failed"` / all).
    - p95 `messaging_process_duration_seconds_bucket`.
    - Lag `max by (redpanda_group) (redpanda_kafka_consumer_group_lag_max)`.
    - Outbox lag `max by (job) (platform_outbox_lag_seconds)`.
  - `metric-names.txt`: one Prometheus metric name per line (the 8 names: 3 http suffixes, 3 messaging suffixes, outbox, lag_max; add lag_sum only if used).
  - Validator check `dashboards`: each JSON parses, `uid` is unique, every panel target has the `datasource.uid` set, and every metric identifier extracted from `targets[].expr` (regex `[a-z_][a-z0-9_]*(_bucket|_count|_sum|_seconds|_lag_max|_lag_sum)\b`) is listed in `metric-names.txt`.

- [ ] **Step 1: Add the `dashboards` check** and a fixture `scripts/testdata/k8s-validate/bad-dashboard/` (expr uses `http_requests_total`) to `--self-test`.
- [ ] **Step 2: Run** `--self-test` → PASS; run the full validator → FAIL (dashboards dir missing).
- [ ] **Step 3: Write the provider, dashboards, `metric-names.txt` and mounts.**
- [ ] **Step 4: Run** the full validator → PASS. Run `docker compose -f deploy/local/docker-compose.yml config -q` → exit 0. Optional light local check (lgtm only, about 1 GB): `docker compose -f deploy/local/docker-compose.yml up -d lgtm`, then `curl -s localhost:3000/api/search?query=Platform` lists both uids, then `down`.
- [ ] **Step 5: Commit** `feat(observability): RED and messaging Grafana dashboards as code`

---

### Task 6: kind e2e job in CI (full stack + smoke + observability check)

**Decision: deploy the full stack, not a subset.** The sum of memory limits is about 5.5 GiB (apps 2.6 GiB + data 1.1 GiB + observability 1.3 GiB + mailpit), well inside the runner's 16 GB. CPU requests total about 1.3 cores of 4. Every existing smoke script needs a different subset, so a subset would leave flows untested. Images are built in the job from the existing `type=gha,scope=<image>-amd64` caches (read-only) and loaded into kind. No registry or push is needed, so the job also works on PRs from branches.

**Files:**
- Create: `deploy/k8s/overlays/e2e/kind.yaml` (1 control-plane node, pinned `kindest/node` image by digest), `scripts/k8s-e2e.sh`, `scripts/e2e-observability.sh`, `.github/workflows/_k8s-e2e.yml`
- Modify: `.github/workflows/ci.yml` (job `k8s-e2e`, `ci-gate.needs`), `CLAUDE.md` (Commands + Repo map: `deploy/k8s/{base,overlays/{prod,e2e},platform}`, `scripts/k8s-validate.sh`, CI jobs `k8s-manifests`/`k8s-e2e`)

**Interfaces:**
- Consumes: the e2e overlay (Task 4), dashboards (Task 5), and the metric names (Tasks 1–2).
- Produces:
  - `scripts/k8s-e2e.sh` assumes a current kube context and images already loaded. It does:
    1. `kubectl apply -k deploy/k8s/overlays/e2e`. On "no matches for kind" from a first apply, retry once.
    2. `kubectl -n data rollout status statefulset/postgres statefulset/redpanda --timeout=300s`, then `kubectl -n observability rollout status deploy/lgtm ds/otel-collector --timeout=300s`, then `kubectl -n apps wait --for=condition=Available deploy --all --timeout=600s`.
    3. Background port-forwards on fixed local ports: order 8080, notify 8081, chart 8082, reading 8083, identity 8084, payment 8085, web 3001→3000, mailpit 8025, lgtm 3000/9090/3100/3200. Each one is waited on with a deadline loop until `curl -fsS` answers.
    4. Runs in order: `scripts/smoke-flow.sh`, `smoke-chart.sh`, `smoke-reading.sh`, `smoke-identity-payment.sh`, `smoke-qa.sh`, `smoke-web.sh http://localhost:3001`, `scripts/e2e-observability.sh`.
    5. The first failure exits non-zero. A trap kills the port-forwards.
  - `scripts/e2e-observability.sh [PROM_URL] [TEMPO_URL] [LOKI_URL]` (defaults `http://localhost:9090`, `:3200`, `:3100`). Each assertion is a deadline loop of 120 s polling every 5 s. It prints `OK <check>` or `FAIL <check>: <last response>`.
    - `trace-cross-service`: Tempo `GET /api/search?q=<urlencoded {resource.service.name="order-svc"} >> {resource.service.name="notify-svc"}>&start=<t0>&end=<now>` returns ≥ 1 trace (spec §5.4, §16.3).
    - `red-http`: Prometheus `count by (job) (http_server_request_duration_seconds_count)` includes `job` values `order-svc, notify-svc, chart-svc, reading-tuvi, identity-svc, payment-svc`.
    - `red-consumer`: `messaging_process_duration_seconds_count{outcome="processed"}` exists for `job="notify-svc"` and `job="identity-svc"`.
    - `consumer-lag`: `redpanda_kafka_consumer_group_lag_max` has a series with `redpanda_group="notify-svc"`.
    - `outbox-lag`: `platform_outbox_lag_seconds{job="order-svc"}` exists.
    - `logs-with-trace`: Loki `query_range` `{service_name="notify-svc"} |= "event processed"` returns ≥ 1 line whose `trace_id` field is 32 hex chars.
    - `dashboards`: Grafana `GET :3000/api/dashboards/uid/platform-red` and `/platform-messaging` return 200.
  - `.github/workflows/_k8s-e2e.yml` (`workflow_call`, no inputs) has one job `kind-e2e` (`ubuntu-24.04`, `timeout-minutes: 60`, `permissions: contents: read`):
    1. checkout, `docker/setup-buildx-action`.
    2. 7× `docker/build-push-action` with `load: true`, `push: false`, `platforms: linux/amd64`, `tags: <svc>:e2e`, `cache-from: type=gha,scope=<svc>-amd64` (no `cache-to`). Web uses `build-args: WEB_ORIGIN=http://localhost:3001`.
    3. Create the cluster with the kind binary downloaded at a pinned version with sha256 verified, using `deploy/k8s/overlays/e2e/kind.yaml`.
    4. `kind load docker-image <svc>:e2e` ×7.
    5. `bash scripts/k8s-e2e.sh`.
    6. On failure, a diagnostics step writes `kubectl get all -A -o wide`, `kubectl get events -A --sort-by=.lastTimestamp`, `kubectl describe pods -n apps`, and `kubectl logs --all-containers --prefix -n apps|data|observability --tail=500` into `k8s-e2e-diagnostics/`. That folder is uploaded as artifact `k8s-e2e-diagnostics`.
  - `ci.yml` job `k8s-e2e`: `needs: changes`, `if: needs.changes.outputs.k8s == 'true' || needs.changes.outputs.root == 'true' || (github.event_name == 'push' && github.ref == 'refs/heads/main')`, `uses: ./.github/workflows/_k8s-e2e.yml`. Add it to `ci-gate.needs`. Each merge to main therefore runs the full kind e2e (spec §16.3). On PRs it runs only when deploy, scripts or workflows change.

- [ ] **Step 1: Write `scripts/e2e-observability.sh` first.** Run it with no stack (`bash scripts/e2e-observability.sh http://127.0.0.1:1 http://127.0.0.1:1 http://127.0.0.1:1`, with the deadline env `E2E_OBS_DEADLINE_S=5` so the check is quick) → exits non-zero with `FAIL trace-cross-service` (proves the checks can fail).
- [ ] **Step 2: Write `scripts/k8s-e2e.sh`, `kind.yaml`, `_k8s-e2e.yml` and the `ci.yml` job.** Run actionlint (as in Task 3) → no errors. Run `bash -n` on both scripts → OK.
- [ ] **Step 3: Push the branch and open a PR.** The PR touches `deploy/**`, so `k8s-e2e` runs. Read the job log; the expected evidence is every `OK <smoke>` line and the 7 `OK <check>` lines from `e2e-observability.sh`. On failure, read the diagnostics artifact first and record a lesson (`retro` skill) for anything not a registry flake. If metric names differ from Global Constraints because of lgtm's OTLP translation strategy, update `metric-names.txt`, the dashboards, the checks and the Global Constraints line in this plan together, then rerun.
- [ ] **Step 4: Update `CLAUDE.md`** (Repo map line for `deploy/k8s`, Commands: `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:<pinned> bash scripts/k8s-validate.sh` and "kind e2e runs only in CI job `k8s-e2e`; never locally").
- [ ] **Step 5: Commit** `ci(k8s): kind e2e of the full stack with smoke and observability checks`. Then follow the normal flow: CI green → squash-merge → confirm `k8s-e2e` is green on the `main` push run as well.

---

## Self-review notes

- Spec coverage: §14.4 Kustomize, observability stack and dashboards are covered by Tasks 3–5. §7.5 kustomize + kubeconform by Task 3. §10 RED for HTTP and consumer, outbox lag, consumer lag by Tasks 1–2 and Redpanda in Task 3. Logs to Loki with service and level by the Task 3 collector. Dashboards as code by Task 5. 100 % trace sampling is already the SDK default. §16.3 kind e2e with order → notify → trace by Task 6. §11 no plaintext secrets by Task 4. Deferred to Plan 4: alerts, retention, Argo CD, Sealed Secrets, cloudflared, backup and JVM/node panels. Web (Next.js) RED is not instrumented in this plan; its traffic is visible as upstream calls in the backends' RED.
- Names used across tasks: `http_metrics_layer`, `DURATION_BUCKETS_S`, `messaging.process.duration`, `platform.outbox.lag`, `<svc>-secrets`, `k8s-manifests`, `k8s-e2e`, `kind-e2e`, `scripts/k8s-validate.sh`, `scripts/k8s-e2e.sh`, `scripts/e2e-observability.sh`. They are consistent between the Interfaces blocks and the steps.
