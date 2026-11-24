# Plan 2 — Rust platform-core + notify-svc + cross-service trace

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Rust service `notify-svc` built from a shared crate `platform-core` consumes `OrderPlaced` from `orders.order-placed.v1` idempotently, stores notifications in its own Postgres DB, exposes `GET /notifications`, and continues the trace started by `order-svc`, so one `POST /orders` yields one trace spanning both services — all built and tested in CI and runnable in the light local stack.

**Architecture:** Cargo workspace at repo root with `libs/rust/platform-core` (telemetry, HTTP errors/health, idempotent Kafka consumer runner) and `services/notify-svc` (hexagonal: `domain` / `application` / `adapters::{http,db,messaging}`). Rust toolchain runs in a Docker dev image (no native Rust on the Windows dev machine). Same cross-language contracts as Java: JSON log fields, RFC 9457 + `trace_id`, `/health/live|ready`, event envelope + W3C `traceparent`.

**Tech Stack:** Rust stable (pinned in `rust-toolchain.toml`), tokio, axum, tower-http, sqlx (Postgres, migrations), rdkafka (`cmake-build`), serde/serde_json, thiserror/anyhow, tracing + tracing-subscriber + tracing-opentelemetry + opentelemetry-otlp, testcontainers(-modules), cargo-deny, cargo-llvm-cov, cargo-chef. Versions: latest stable on crates.io at implementation time, mutually compatible (opentelemetry family must match tracing-opentelemetry's).

**Spec:** `docs/superpowers/specs/2026-11-23-platform-foundation-design.md` (§5.1, §5.3, §5.4, §7, §10). Plan 1 for the Java side: `docs/superpowers/plans/2026-11-23-plan-1-foundation-and-order-svc.md`.

## Global Constraints

- Logs: JSON on stdout, one object per line, mandatory fields `timestamp, level, service, trace_id, span_id, message` (exact names, same as Java `PlatformLogFormatter`); extra fields under `ctx`; errors under `error.type` / `error.stack` (Rust: `error.message` acceptable in place of stack). Empty string when no trace context.
- HTTP errors: RFC 9457 `application/problem+json` with `type = "urn:problem:<slug>"`, `title`, `status`, `detail`, `trace_id`; 4xx validation errors add `errors: [{field, message}]`; 500 detail is exactly `"Unexpected error"`; never leak internals.
- Health: `/health/live` (process up) and `/health/ready` (DB reachable) → `{"status":"UP"}` 200 / `{"status":"DOWN"}` 503.
- Event envelope (consumed): `{event_id, event_type, occurred_at, data}`; headers `event_id`, `event_type`, `traceparent`. `data` may contain additional fields (v1 additive rule). Consumer idempotent on `event_id`.
- Topic `orders.order-placed.v1`; `data = {order_id, customer_id, total (string decimal), item_count}`.
- Database per service: `notify-svc` owns database `notify`, user `notify`.
- Config via env: `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `PORT` (default `8081`), `OTEL_EXPORTER_OTLP_ENDPOINT` (unset → no exporter), `OTEL_SERVICE_NAME` (default `notify-svc`), `KAFKA_GROUP_ID` (default `notify-svc`), `RUST_LOG` (default `info`).
- Images: non-root, multi-arch `linux/amd64` + `linux/arm64`, tag `sha-<commit>` + `main`, `ghcr.io/<owner>/notify-svc`.
- Quality gates: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo deny check`, line coverage ≥ 80% for the workspace excluding `main.rs` and test fixtures.
- Platform migrations (`processed_event` table) are tracked separately from service migrations (Rust mirror of Plan 1 ruling I4).
- Dev machine is weak (2 cores / 12GB): all cargo commands run through `scripts/cargo.sh` (Docker) with persistent cargo registry + target caches; local stack total ≤ 3GB.

## Review Focus

1. **Duplicate delivery** (same `event_id` twice, e.g. relay re-send) → exactly one notification row; second message's offset still committed. Test in Task 4 and Task 6.
2. **Malformed message** (not JSON, missing `event_id`, wrong `event_type`, `data` missing required field) → logged at WARN with topic/partition/offset, offset committed (skipped), consumer keeps running. Test in Task 4.
3. **DB unavailable while consuming** → handler fails, offset NOT committed, message retried after backoff (bounded, e.g. 1s→30s), no crash; processed once DB returns. Test in Task 4.
4. **Kafka unavailable at startup** → HTTP server still starts, `/health/live` 200; consumer retries connecting in background. Test in Task 6.
5. **`GET /notifications` bounds** → `limit` default 50, max 200, `limit=0`/negative/non-number → 400 problem with `errors[0].field == "limit"`. Test in Task 6.

---

### Task 1: Rust toolchain in Docker, Cargo workspace, Rust conventions

**Files:**
- Create: `tools/rust-dev/Dockerfile`, `scripts/cargo.sh`, `scripts/cargo.ps1`, `Cargo.toml` (workspace), `rust-toolchain.toml`, `deny.toml`, `.cargo/config.toml` (if needed), `.claude/skills/rust-service-conventions/SKILL.md`
- Modify: `CLAUDE.md` (Commands + Repo map + Rust conventions pointer), `.gitignore` (root `target/` already), `.dockerignore`

**Interfaces:**
- Produces: `scripts/cargo.sh <cargo args>` runs `cargo` inside image `platform-rust-dev:<toolchain>` with repo mounted at `/work`, named volumes `platform-cargo-registry`, `platform-cargo-git`, and `CARGO_TARGET_DIR=/work/target/docker`; mounts `/var/run/docker.sock` so testcontainers work; passes `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`; works from Git Bash (`MSYS_NO_PATHCONV=1`) and Linux. Image includes rustfmt, clippy, cmake, build-essential, pkg-config, libssl-dev, libsasl2-dev, cargo-deny, cargo-llvm-cov (+ llvm-tools), cargo-chef.
- Workspace members: `libs/rust/platform-core`, `services/notify-svc`; `[workspace.dependencies]` holds every shared version; `[workspace.lints]` with `clippy::pedantic` warn-level subset decided by builder (must keep `-D warnings` green).

- [ ] **Step 1:** Write dev image + wrappers; `scripts/cargo.sh --version` prints the pinned toolchain; image builds in ≤ 15 min on the dev machine (report time).
- [ ] **Step 2:** Workspace with two empty member crates (lib + bin) that compile; `scripts/cargo.sh fmt --check`, `clippy --all-targets -- -D warnings`, `deny check` all pass.
- [ ] **Step 3:** `rust-service-conventions/SKILL.md` (< 120 lines): module layout mirroring Java hexagonal (`domain` has no tokio/axum/sqlx/rdkafka imports — enforced by a test that greps `src/domain/**` for forbidden `use` paths), error strategy (`thiserror` in domain/application, `anyhow` only in `main`/adapters wiring), logging contract, test pyramid (unit → application with in-memory fakes → adapter tests → testcontainers ITs in `tests/`), naming (`*Port` traits, `*Adapter` structs), review checklist; how to run everything via `scripts/cargo.sh`.
- [ ] **Step 4:** Commit `build(rust): dockerized toolchain, cargo workspace and Rust conventions`.

---

### Task 2: platform-core telemetry (JSON logs + OTLP traces)

**Files:**
- Create: `libs/rust/platform-core/src/lib.rs`, `src/telemetry/mod.rs`, `src/telemetry/json_layer.rs`
- Test: `libs/rust/platform-core/src/telemetry/json_layer.rs` (unit tests module), `libs/rust/platform-core/tests/telemetry.rs`

**Interfaces:**
- Produces: `pub fn init(service_name: &str) -> anyhow::Result<TelemetryGuard>` — installs a `tracing` subscriber with the JSON layer (writer = stdout), `EnvFilter` from `RUST_LOG` (default `info`), and when `OTEL_EXPORTER_OTLP_ENDPOINT` is set an OTLP (http/protobuf) tracer via `tracing-opentelemetry`; sets the global W3C `TraceContextPropagator`. `TelemetryGuard` flushes/shuts down the tracer provider on drop.
- `pub struct JsonLayer<W>` usable with any `MakeWriter` (tests capture output).
- `pub fn current_trace_id() -> String` — 32-hex trace id of the current span's OTel context, `""` when none/invalid (used by HTTP errors).

- [ ] **Step 1: Failing tests:** `json_line_has_mandatory_fields` (`level == "INFO"`, `service == "notify-svc"`, ISO-8601 UTC `timestamp`, one line ending `\n`); `no_trace_context_gives_empty_ids`; `inside_otel_span_ids_match_span_context` (SDK tracer with in-memory exporter, assert `trace_id`/`span_id` equal the span's); `event_fields_go_under_ctx` (`info!(order_id = "42", "x")` → `ctx.order_id == "42"`); `error_field_maps_to_error_object` (`error!(error = %e, "boom")` → `error.message`); `message_with_quote_backslash_newline_round_trips`.
- [ ] **Step 2:** Run `scripts/cargo.sh test -p platform-core` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS; fmt/clippy clean.
- [ ] **Step 5:** Commit `feat(platform-core): JSON log layer and OTLP tracing init`.

---

### Task 3: platform-core HTTP (Problem Details, health, trace propagation)

**Files:**
- Create: `libs/rust/platform-core/src/http/{mod.rs, problem.rs, health.rs, tracing_mw.rs}`
- Test: unit tests in each module + `libs/rust/platform-core/tests/http.rs` (axum `tower::ServiceExt::oneshot`)

**Interfaces:**
- Produces:
  - `pub enum ApiError { NotFound { resource: &'static str, id: String }, Validation(Vec<FieldError>), BadRequest(String), Internal(anyhow::Error) }`, `pub struct FieldError { pub field: String, pub message: String }`; `impl IntoResponse` → problem JSON (slugs: `not-found`, `validation`, `bad-request`, `internal-error`; titles `Not Found`, `Bad Request`, `Bad Request`, `Internal Server Error`; `NotFound` detail `"<resource> <id> not found"`; `Internal` logs at ERROR with the error, body detail `"Unexpected error"`); `trace_id` from `telemetry::current_trace_id()`.
  - `pub trait ReadinessCheck: Send + Sync { async fn ready(&self) -> bool; }` (use `async_trait` or native AFIT with boxing — builder's call), `pub fn health_router(check: Arc<dyn ReadinessCheck>) -> Router`.
  - `pub fn trace_layer() -> ...` tower layer that extracts W3C `traceparent` from request headers into the request span's parent and names spans `"<METHOD> <matched route>"` with `http.request.method`, `http.route`, `http.response.status_code`.
  - JSON body rejection (axum `JsonRejection`) and query rejection map to `ApiError::BadRequest` problem (never axum's plain-text default).

- [ ] **Step 1: Failing tests:** not_found → 404 problem (content type `application/problem+json`, `type == "urn:problem:not-found"`, detail text, `trace_id` key present); validation → 400 with `errors[0].field`; internal → 500 `detail == "Unexpected error"` and body lacks the inner error text; malformed JSON body → 400 problem; `/health/live` 200 UP; `/health/ready` 200 when check true, 503 DOWN when false; request with `traceparent` header → handler's `current_trace_id()` equals the header's trace id.
- [ ] **Step 2:** `scripts/cargo.sh test -p platform-core` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS; fmt/clippy clean.
- [ ] **Step 5:** Commit `feat(platform-core): problem details, health and trace propagation for axum`.

---

### Task 4: platform-core idempotent Kafka consumer runner

**Files:**
- Create: `libs/rust/platform-core/src/messaging/{mod.rs, envelope.rs, consumer.rs, idempotency.rs}`, `libs/rust/platform-core/migrations/0001_processed_event.sql`, `src/db.rs` (`migrate_platform`)
- Test: unit tests (envelope parsing) + `libs/rust/platform-core/tests/consumer_it.rs` (testcontainers Postgres 17 + Redpanda)

**Interfaces:**
- Produces:
  - `pub struct Envelope { pub event_id: Uuid, pub event_type: String, pub occurred_at: DateTime<Utc>, pub data: serde_json::Value }` (unknown top-level fields rejected? → no: ignore unknown, require the four).
  - `#[async_trait] pub trait EventHandler: Send + Sync { fn event_type(&self) -> &'static str; async fn handle(&self, tx: &mut sqlx::Transaction<'_, Postgres>, event: &Envelope) -> anyhow::Result<()>; }`
  - `pub struct ConsumerConfig { bootstrap_servers, group_id, topic, consumer_name: &'static str, max_backoff: Duration (default 30s) }`; `pub async fn run_consumer(cfg, pool: PgPool, handler: Arc<dyn EventHandler>, shutdown: CancellationToken) -> anyhow::Result<()>`.
  - `pub async fn migrate_platform(pool: &PgPool) -> anyhow::Result<()>` — applies `libs/rust/platform-core/migrations` tracked separately from service migrations (own migrations table, e.g. schema `platform` or a custom table name — builder verifies what sqlx supports and states it); table `processed_event(consumer text, event_id uuid, processed_at timestamptz not null default now(), primary key (consumer, event_id))`.
- Algorithm per message (manual commit, `enable.auto.commit=false`, `auto.offset.reset=earliest`):
  ```
  parent = propagator.extract(headers["traceparent"])
  span "<topic> process" (kind consumer, parent = parent, messaging.* attributes)
  envelope = parse(payload) → on error: WARN(topic, partition, offset, reason); commit; continue
  if envelope.event_type != handler.event_type(): DEBUG skip; commit; continue
  loop with backoff 1s doubling to max_backoff:
      tx = pool.begin()
      inserted = INSERT INTO processed_event (consumer, event_id) VALUES (...) ON CONFLICT DO NOTHING
      if inserted == 0: tx.rollback(); commit offset; break          # duplicate
      handler.handle(&mut tx, &envelope)?; tx.commit()?; commit offset; break
      on Err: WARN with error; sleep(backoff); (respect shutdown)
  ```

- [ ] **Step 1: Failing tests:** envelope unit tests (valid; missing `event_id` → Err; extra `data` fields allowed; extra top-level field ignored). ITs: `handles_event_once_and_commits` (handler writes a row; assert 1 row, offset committed); `duplicate_event_id_processed_once` (Review Focus 1); `malformed_messages_are_skipped_and_consumer_continues` (non-JSON, missing event_id, then a valid one → valid processed) (Review Focus 2); `db_down_retries_until_db_back` (pause Postgres container, publish, unpause after 3s → processed once, no panic) (Review Focus 3); `continues_trace_from_traceparent_header` (publish with known traceparent → span recorded via in-memory exporter has that trace id and the remote parent span id).
- [ ] **Step 2:** `scripts/cargo.sh test -p platform-core` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS (report IT time); fmt/clippy clean.
- [ ] **Step 5:** Commit `feat(platform-core): idempotent Kafka consumer runner with trace continuation`.

---

### Task 5: notify-svc domain + application

**Files:**
- Create: `services/notify-svc/src/{lib.rs, domain/mod.rs, domain/notification.rs, application/mod.rs, application/ports.rs, application/service.rs}`
- Test: unit tests in modules; `services/notify-svc/tests/architecture.rs` (domain purity check)

**Interfaces:**
- Produces:
  - `pub struct Notification { id: Uuid, order_id: Uuid, customer_id: String, channel: Channel, message: String, created_at: DateTime<Utc> }` with getters; `pub enum Channel { Log }` (serialized `"log"`).
  - `pub struct OrderPlaced { order_id: Uuid, customer_id: String, total: String, item_count: u32 }` (application input; parsed from envelope `data` in Task 6).
  - `Notification::for_order_placed(e: &OrderPlaced, id: Uuid, now: DateTime<Utc>) -> Result<Notification, DomainError>` — message exactly `"Order <order_id> placed: <item_count> item(s), total <total>"`; rejects blank customer_id, item_count 0, total not matching `^[0-9]+\.[0-9]{2}$`.
  - `#[async_trait] pub trait NotificationRepositoryPort { async fn save(&self, tx: &mut Transaction<'_, Postgres>, n: &Notification) -> anyhow::Result<()>; async fn list_recent(&self, limit: u32) -> anyhow::Result<Vec<Notification>>; }` (tx type from sqlx is allowed in ports — ruling: idempotency requires the handler's insert to share the consumer's transaction).
  - `pub struct NotificationService<R>`: `async fn record(&self, tx, e: &OrderPlaced) -> Result<Notification, AppError>`; `async fn list(&self, limit: Option<u32>) -> Result<Vec<Notification>, AppError>` (default 50, max 200, 0 → `AppError::InvalidLimit`).
  - Clock and id generation injected (`Clock` trait / closure) for deterministic tests.

- [ ] **Step 1: Failing tests:** message text exact for `{order_id, item_count: 3, total: "25.50"}`; rejections; list default/max/zero; architecture test: no `use (tokio|axum|sqlx|rdkafka)` under `src/domain`.
- [ ] **Step 2:** `scripts/cargo.sh test -p notify-svc` → FAIL. **Step 3:** implement. **Step 4:** PASS. **Step 5:** commit `feat(notify): notification domain and use cases`.

---

### Task 6: notify-svc adapters, wiring and contracts

**Files:**
- Create: `services/notify-svc/src/main.rs`, `src/config.rs`, `src/adapters/{http.rs, db.rs, messaging.rs}`, `services/notify-svc/migrations/0001_notifications.sql`, `contracts/openapi/notify-svc.yaml`
- Test: `services/notify-svc/tests/{http_api.rs, flow_it.rs, contract.rs}`

**Interfaces:**
- Consumes: Tasks 2–5 (`telemetry::init`, `ApiError`, `health_router`, `trace_layer`, `run_consumer`, `migrate_platform`, `EventHandler`, `NotificationService`).
- Produces (HTTP): `GET /notifications?limit=<n>` → `200 [{id, orderId, customerId, channel, message, createdAt}]` newest first; errors as problems. Port from `PORT` (default 8081).
- Table `notifications(id uuid pk, order_id uuid not null, customer_id text not null, channel text not null, message text not null, created_at timestamptz not null)` + index on `created_at desc`.
- Startup order: telemetry → config (fail fast on missing `DB_URL`/`KAFKA_BOOTSTRAP_SERVERS`) → pool → `migrate_platform` → service migrations → HTTP server + consumer task (consumer failures never stop HTTP) → graceful shutdown on SIGTERM/Ctrl-C (CancellationToken; stop consumer, drain HTTP, flush telemetry).
- `OrderPlacedHandler` (EventHandler, event_type `"OrderPlaced"`): parse `data` into `OrderPlaced`; invalid data → log WARN and return Ok (skip, don't retry forever); else `service.record`.

- [ ] **Step 1:** Write `notify-svc.yaml` (OpenAPI 3.1, `Notification`, `Problem` schemas, `limit` param `minimum: 1, maximum: 200`).
- [ ] **Step 2: Failing tests:** `http_api.rs` (in-memory repo fake): list returns newest first with camelCase fields; `limit=0`, `limit=201`, `limit=-1`, `limit=abc` → 400 problem with `errors[0].field == "limit"` (Review Focus 5); `/health/live` 200. `contract.rs`: a sample response body and 400 problem validate against `notify-svc.yaml` schemas (use `jsonschema` crate on schemas extracted from YAML via `serde_yaml`); a fixture event built exactly like order-svc's envelope validates against `contracts/events/order-placed.v1.schema.json` and parses into `OrderPlaced`. `flow_it.rs` (testcontainers Postgres + Redpanda, app started in-process on a random port): publish valid envelope with `traceparent` → within 10s `GET /notifications` contains message for that order; publish same `event_id` twice → one row (Review Focus 1); start app with Kafka pointing to a closed port → `/health/live` 200 within 5s (Review Focus 4).
- [ ] **Step 3:** `scripts/cargo.sh test -p notify-svc` → FAIL. **Step 4:** implement. **Step 5:** PASS + fmt/clippy/deny + `scripts/cargo.sh llvm-cov --workspace --fail-under-lines 80 --ignore-filename-regex 'main\.rs|tests/'`.
- [ ] **Step 6:** Commit `feat(notify): HTTP API, persistence and OrderPlaced consumer`.

---

### Task 7: Image, local stack and cross-service trace

**Files:**
- Create: `services/notify-svc/Dockerfile`, `scripts/smoke-flow.sh`
- Modify: `deploy/local/docker-compose.yml`, `deploy/local/init-db.sql`, `deploy/local/.env.example`, `CLAUDE.md` (local commands)

**Interfaces:**
- Image: multi-stage with cargo-chef (planner → cook deps → build `--release -p notify-svc`), runtime `gcr.io/distroless/cc-debian12:nonroot` (uid 65532) or debian-slim + non-root user if librdkafka needs extra shared libs (builder verifies with `ldd`); listens on `PORT`.
- Compose: `init-db.sql` adds db/user `notify`; service `notify-svc` (profile `app`, `mem_limit: 128m`, loopback port `127.0.0.1:8081:8081`, env per Global Constraints with `OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318`, depends_on postgres healthy + redpanda healthy); total stack `mem_limit` ≤ 3GB.
- `scripts/smoke-flow.sh BASE_ORDER BASE_NOTIFY`: run `smoke-order.sh`, capture order id, poll `GET /notifications` up to 15s until a message contains the id; exit non-zero otherwise.

- [ ] **Step 1:** Build: `docker build -f services/notify-svc/Dockerfile -t notify-svc:local .` succeeds (report build time); container runs as non-root.
- [ ] **Step 2:** `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build`; `scripts/smoke-flow.sh http://localhost:8080 http://localhost:8081` → `OK`.
- [ ] **Step 3:** Tempo: the trace of that `POST /orders` contains spans from **both** services: `order-svc` server span → `orders.order-placed.v1 publish` (producer) → `orders.order-placed.v1 process` (notify-svc consumer) with the DB insert under it. Include trimmed evidence. notify-svc log lines for the consume carry the same `trace_id`. If not one trace → DONE_WITH_CONCERNS with evidence.
- [ ] **Step 4:** `docker stats --no-stream` total ≤ 3GB; `down -v`.
- [ ] **Step 5:** Commit `feat(notify): container image, local stack and end-to-end flow`.

---

### Task 8: CI for Rust + ship

**Files:**
- Create: `.github/workflows/_rust-service.yml`
- Modify: `.github/workflows/ci.yml` (filter `notify: services/notify-svc/**|libs/rust/**|Cargo.toml|Cargo.lock|rust-toolchain.toml|deny.toml|contracts/**`, job `notify-svc`, `ci-gate.needs` += `notify-svc`), `.github/dependabot.yml` (`cargo` ecosystem at `/`, grouped minor/patch; docker at `/services/notify-svc`)

**Interfaces:**
- Reusable workflow inputs: `package` (`notify-svc`), `image` (`notify-svc`), `dockerfile`, `publish` (bool). Jobs: `verify` (toolchain from `rust-toolchain.toml` via `dtolnay/rust-toolchain` pinned by SHA or rustup; `Swatinem/rust-cache` pinned by SHA; install cmake/libsasl2-dev; fmt --check, clippy -D warnings, `cargo deny check` (taiki-e/install-action pinned by SHA), `cargo llvm-cov --workspace --fail-under-lines 80 ...` running ITs with Docker), `image-check` (PR, amd64, push:false), `image` matrix amd64 `ubuntu-24.04` / arm64 `ubuntu-24.04-arm` push-by-digest + `manifest` (same pattern as `_java-service.yml`, `packages: write` only there). All third-party actions pinned by full SHA with version comment.

- [ ] **Step 1:** Write workflows; push branch; open PR; `ci-gate` green (fix + lesson in `docs/lessons/` for every CI failure, per `retro`).
- [ ] **Step 2:** Verify a docs-only change still yields `ci-gate` pass (path filter skips both services) — use the PR's own history or a throwaway commit reverted before merge.
- [ ] **Step 3:** Do NOT merge — final whole-branch review first (controller merges).

---

## Done criteria for Plan 2

- `main` CI green with `notify-svc` jobs; `ghcr.io/<owner>/notify-svc:main` lists amd64 + arm64.
- Locally: `smoke-flow.sh` OK; one trace spans order-svc → Kafka → notify-svc in Grafana; logs of both services share the `trace_id`.
- Duplicate/malformed/DB-down behaviours proven by ITs.
