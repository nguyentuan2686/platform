# Plan 1 — Repo Foundation + Java Platform Starter + order-svc

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A public GitHub monorepo with green CI that builds and tests a shared Java platform starter and the first microservice `order-svc` (place/get orders, publish `OrderPlaced` via transactional outbox to Redpanda), shipped as a multi-arch image to GHCR, runnable locally with a light compose stack.

**Architecture:** Maven multi-module (root aggregator) holding `libs/java/platform-starter` (Spring Boot auto-configuration: JSON log format, RFC 9457 errors with `trace_id`, health probes, outbox) and `services/order-svc` (hexagonal: `domain` / `application` / `adapter`). GitHub Actions runs path-filtered CI and pushes images. Claude workflow files (`CLAUDE.md`, `.claude/`) and the lessons loop are created first so every later task follows them.

**Tech Stack:** Java 21, Spring Boot latest stable 4.x (read from `https://start.spring.io/metadata/client` at Task 2), Maven 3.9, Spring Data JDBC, Flyway, Spring Kafka, PostgreSQL 17, Redpanda, Testcontainers, JUnit 5, AssertJ, ArchUnit, Spotless (google-java-format), JaCoCo, OpenTelemetry Java agent, GitHub Actions, GHCR.

**Spec:** `docs/superpowers/specs/2026-11-23-platform-foundation-design.md`

**Later plans (not in scope here):** Plan 2 Rust template + `notify-svc` + cross-service trace locally; Plan 3 Kustomize manifests + observability stack + kind e2e in CI; Plan 4 host bootstrap + Argo CD CD + alerts (Telegram) + backup; Plan 5 `new-service` scaffolding + remaining skills.

## Global Constraints

- Monorepo; GitHub repo `platform`, **public**, owner = the logged-in `gh` account.
- Java 21; base package `dev.platform`; Maven groupId `dev.platform`.
- Every HTTP service exposes `/health/live` and `/health/ready`.
- Logs: JSON on stdout, one object per line, mandatory fields `timestamp, level, service, trace_id, span_id, message` (exact names — Rust services will emit the same).
- HTTP errors: RFC 9457 Problem Details (`application/problem+json`) with extra property `trace_id`. Never leak stack traces.
- Config via env vars (12-factor); no plaintext secrets in Git.
- Database per service: `order-svc` owns database `orders`, user `orders`.
- Events: JSON envelope `{event_id, event_type, occurred_at, data}`; context propagated with W3C `traceparent` Kafka header; consumers must be idempotent on `event_id`.
- Images: non-root, multi-arch `linux/amd64` + `linux/arm64`, tag `sha-<commit>`, pushed to `ghcr.io/<owner>/<service>`.
- Coverage gate ≥ 80% line coverage for `domain` and `application` packages.
- Trunk-based: short branch → PR → CI green → Claude squash-merges (no human review required).
- Dev machine is weak (2 cores / 12GB): local stack must stay ≤ ~3GB; heavy verification happens in CI.

## Review Focus

1. **Relay crashes after Kafka send but before marking published** → event re-sent with the *same* `event_id` (at-least-once). Test in Task 5.
2. **Two relay instances run concurrently** (2 replicas) → each event sent exactly once per relay cycle thanks to `FOR UPDATE SKIP LOCKED`. Test in Task 5.
3. **Broker unavailable** → `POST /orders` still returns 201; row stays unpublished; relay logs a warning and retries next cycle without crashing the scheduler. Test in Task 5 (relay) and Task 7 (API).
4. **Invalid order payload** (no items, quantity ≤ 0, negative price, blank customerId, malformed JSON) → 400 Problem Details listing field errors, with `trace_id`; never 500. Test in Task 7.
5. **Unexpected exception** → 500 Problem Details with generic `detail`, no stack trace or exception class in body. Test in Task 4.

---

### Task 1: Repository bootstrap, Claude working rules, lessons loop

**Files:**
- Create: `.gitignore`, `.gitattributes`, `README.md`, `CLAUDE.md`, `docs/lessons/README.md`, `docs/adr/0001-record-architecture-decisions.md`, `docs/adr/0002-platform-foundation.md`
- Move (already exist): `docs/superpowers/specs/*`, `docs/superpowers/plans/*`

**Interfaces:**
- Produces: GitHub repo `<owner>/platform` with default branch `main`; `CLAUDE.md` rules every later task obeys.

- [ ] **Step 1:** `git init -b main` in `C:\Claude_AI`; `.gitattributes` with `* text=auto eol=lf` and `*.cmd eol=crlf`; `.gitignore` covering Maven `target/`, Rust `target/`, IDE files, `.env*` (except `.env.example`), `*.pem`, `*.key`.
- [ ] **Step 2:** Write `CLAUDE.md` containing exactly these sections: *Workflow* (brainstorm → spec → plan → TDD → review → verify → PR → CI → merge → verify-deploy → retro; Claude may squash-merge when CI is green); *Repo map* (spec §6 tree); *Java conventions* (hexagonal packages, `domain` has no Spring imports, constructor injection only, records for DTOs, no `@Autowired` fields, errors via Problem Details); *Logging/observability contract* (Global Constraints lines); *Commands* (`mvn -q verify`, `mvn spotless:apply`, `docker compose -f deploy/local/docker-compose.yml up -d`); *Lessons loop* (spec §9 escalation ladder: 1st time → lesson file; 2nd → rule here; automatable → check in CI/hook); *Never* (commit secrets, push to `main` directly except CD bot, skip hooks).
- [ ] **Step 3:** `docs/lessons/README.md` with the lesson template: `# <title>` / Date / Tags / Symptom / Root cause / Fix / How to catch earlier / Escalation (none | rule | check).
- [ ] **Step 4:** ADR 0001 (Nygard format) and ADR 0002 summarising spec decisions (microservices + monorepo, k3s + Argo CD, Redpanda, outbox, OTel/LGTM, Tailscale, quick tunnel until domain).
- [ ] **Step 5:** Create remote and push:
  Run: `gh repo create platform --public --source . --remote origin --description "Polyglot microservices platform"` then `git add -A && git commit -m "chore: bootstrap repository, working rules and design docs" && git push -u origin main`
  Expected: `gh repo view --json visibility,defaultBranchRef` → `PUBLIC`, `main`.

---

### Task 2: Maven build skeleton + structured JSON log format

**Files:**
- Create: `pom.xml` (root aggregator + pluginManagement), `libs/java/platform-starter/pom.xml`, `libs/java/platform-starter/src/main/java/dev/platform/starter/logging/PlatformLogFormatter.java`, `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `.../src/main/resources/platform-defaults.properties`
- Test: `libs/java/platform-starter/src/test/java/dev/platform/starter/logging/PlatformLogFormatterTest.java`

**Interfaces:**
- Produces: artifact `dev.platform:platform-starter`; class `PlatformLogFormatter implements StructuredLogFormatter<ILoggingEvent>`; services enable it with `logging.structured.format.console=dev.platform.starter.logging.PlatformLogFormatter` (set by starter defaults).

- [ ] **Step 1:** Root `pom.xml`: parent `spring-boot-starter-parent` (version from start.spring.io metadata `bootVersion.default`), `<java.version>21</java.version>`, modules `libs/java/platform-starter`, `services/order-svc` (module added in Task 6). pluginManagement: Spotless (google-java-format, `check` bound to `verify`), JaCoCo (report + `check` rule: LINE ≥ 0.80 for packages `*.domain*`, `*.application*`), Surefire/Failsafe. dependencyManagement: Testcontainers BOM, ArchUnit `archunit-junit5`, `opentelemetry-api` (BOM `opentelemetry-bom`).
- [ ] **Step 2: Write failing test** `PlatformLogFormatterTest`:
  - `formats_mandatory_fields`: event level INFO, message "hello", MDC `{trace_id: "4bf92f3577b34da6a3ce929d0e0e4736", span_id: "00f067aa0ba902b7"}`, service name "order-svc" → parsed JSON has exactly keys ⊇ `timestamp, level, service, trace_id, span_id, message`; `level == "INFO"`; `service == "order-svc"`; `timestamp` is ISO-8601 UTC.
  - `missing_trace_context_emits_empty_strings`: no MDC → `trace_id == ""`, `span_id == ""` (keys still present).
  - `exception_goes_to_error_field_not_message`: event with `IllegalStateException("boom")` → `error.type == "java.lang.IllegalStateException"`, `error.stack` non-empty, `message` unchanged.
  - `other_mdc_entries_are_nested_under_ctx`: MDC `order_id=42` → `ctx.order_id == "42"`.
  - Output is a single line ending with `\n`.
- [ ] **Step 3:** Run `mvn -q -pl libs/java/platform-starter test` → FAIL (class missing).
- [ ] **Step 4:** Implement `PlatformLogFormatter(Environment env)` using Spring Boot `JsonWriter`; service name from `spring.application.name`. Register default `logging.structured.format.console` via an `EnvironmentPostProcessor` (`PlatformDefaultsPostProcessor`, lowest precedence, loads `platform-defaults.properties`).
- [ ] **Step 5:** Run `mvn -q -pl libs/java/platform-starter verify` → PASS (Spotless + tests).
- [ ] **Step 6:** Commit `feat(starter): structured JSON log format with trace context`.

---

### Task 3: Claude automation baseline (hooks + project skills)

**Files:**
- Create: `.claude/settings.json`, `.claude/hooks/format-changed.sh`, `.claude/hooks/block-secrets.sh`, `.claude/skills/java-service-conventions/SKILL.md`, `.claude/skills/retro/SKILL.md`, `.claude/skills/ship/SKILL.md`

**Interfaces:**
- Consumes: Spotless config from Task 2.
- Produces: skills `java-service-conventions`, `retro`, `ship` invocable in later tasks.

- [ ] **Step 1:** `format-changed.sh`: PostToolUse hook on `Edit|Write`; if the edited path ends in `.java` run `mvn -q spotless:apply -pl <module containing file>`; if `.rs` run `rustfmt <file>` when `rustfmt` exists; always exit 0 (formatting must never block).
- [ ] **Step 2:** `block-secrets.sh`: PreToolUse hook on `Bash` matching `git commit`; scan `git diff --cached` for patterns `-----BEGIN .*PRIVATE KEY-----`, `ghp_[A-Za-z0-9]{36}`, `gho_`, `AKIA[0-9A-Z]{16}`, `xox[bp]-`; on match exit 2 with message naming the file.
- [ ] **Step 3:** Verify hooks: create a scratch file containing a fake `ghp_` + 36 chars, `git add` it, attempt commit through Claude → blocked; remove file. Edit a `.java` file with bad indentation → reformatted.
- [ ] **Step 4:** `java-service-conventions/SKILL.md`: package layout, ArchUnit rules, test pyramid (domain unit → application with fakes → adapter slice tests → Testcontainers integration), naming (`*UseCase`, `*Port`, `*Adapter`), error mapping table, logging rules (log at boundaries, never log payload PII), review checklist.
- [ ] **Step 5:** `retro/SKILL.md`: trigger = any CI failure, unexpected test failure, deploy failure, bug found after merge. Steps: write `docs/lessons/YYYY-MM-DD-<slug>.md` from template; grep `docs/lessons` for same tag/root cause; if ≥2 occurrences add a rule to `CLAUDE.md` or the relevant skill; if mechanically checkable, propose (and implement in the same PR) a lint/ArchUnit/CI/hook check; commit as `docs(lessons): ...`.
- [ ] **Step 6:** `ship/SKILL.md`: push branch → `gh pr create --fill` → `gh pr checks --watch` → on red: invoke `retro` then fix → on green `gh pr merge --squash --delete-branch` → `git switch main && git pull`.
- [ ] **Step 7:** Commit `chore(claude): hooks and project skills`.

---

### Task 4: Problem Details + health probes auto-configuration

**Files:**
- Create: `libs/java/platform-starter/src/main/java/dev/platform/starter/web/PlatformProblemHandler.java`, `.../web/PlatformWebAutoConfiguration.java`, `.../error/NotFoundException.java`; add to `platform-defaults.properties`
- Test: `libs/java/platform-starter/src/test/java/dev/platform/starter/web/PlatformProblemHandlerTest.java`, `.../HealthProbesTest.java` (with a tiny `@SpringBootApplication` test app in test sources)

**Interfaces:**
- Produces: `@RestControllerAdvice PlatformProblemHandler extends ResponseEntityExceptionHandler`; public exception `dev.platform.starter.error.NotFoundException(String resource, Object id)` (package `error`, so `application` code never imports a `web` package) → 404; Bean Validation failures → 400 with property `errors: [{field, message}]`; every problem has property `trace_id` (from MDC `trace_id`, `""` if absent).

- [ ] **Step 1: Write failing tests** (MockMvc on test app with endpoints that throw):
  - `not_found_maps_to_404_problem`: `NotFoundException("order", "x")` → 404, content type `application/problem+json`, `title == "Not Found"`, `detail == "order x not found"`, has `trace_id`.
  - `validation_maps_to_400_with_field_errors`: POST `{"name":""}` to endpoint with `@Valid` record `@NotBlank name` → 400, `errors[0].field == "name"`.
  - `malformed_json_maps_to_400`: body `{` → 400, `title == "Bad Request"`.
  - `unexpected_exception_is_500_without_leak`: endpoint throws `RuntimeException("db password=secret")` → 500, `detail == "Unexpected error"`, body does not contain `secret`, `RuntimeException`, or `at dev.`.
  - `trace_id_taken_from_mdc`: with MDC `trace_id=abc` set by a test filter → `trace_id == "abc"`.
  - `HealthProbesTest`: GET `/health/live` → 200 `{"status":"UP"}`; GET `/health/ready` → 200.
- [ ] **Step 2:** Run `mvn -q -pl libs/java/platform-starter test` → FAIL.
- [ ] **Step 3:** Implement handler; log 5xx at ERROR with exception (server side only), 4xx at DEBUG. Defaults: `management.endpoints.web.base-path=/`, `management.endpoints.web.exposure.include=health,prometheus`, `management.endpoint.health.probes.enabled=true`, groups `live` (include `livenessState`) and `ready` (include `readinessState,db` — `db` only when a DataSource exists), `spring.mvc.problemdetails.enabled=true`, `spring.threads.virtual.enabled=true`.
- [ ] **Step 4:** Run `mvn -q -pl libs/java/platform-starter verify` → PASS.
- [ ] **Step 5:** Commit `feat(starter): RFC 9457 errors with trace_id and health probes`.

---

### Task 5: Transactional outbox (writer + relay)

**Files:**
- Create: `libs/java/platform-starter/src/main/java/dev/platform/starter/outbox/{OutboxEvent.java, OutboxWriter.java, JdbcOutboxWriter.java, OutboxRelay.java, OutboxProperties.java, OutboxAutoConfiguration.java}`, `libs/java/platform-starter/src/main/resources/db/platform/V0_1__outbox.sql`
- Test: `libs/java/platform-starter/src/test/java/dev/platform/starter/outbox/OutboxRelayIT.java` (Testcontainers Postgres 17 + Redpanda)

**Interfaces:**
- Produces:
  - `record OutboxEvent(UUID eventId, String eventType, String topic, String key, Instant occurredAt, JsonNode data)`
  - `interface OutboxWriter { void append(OutboxEvent event); }` — must run inside caller's transaction (`@Transactional(propagation = MANDATORY)`); captures current `traceparent` via `GlobalOpenTelemetry.getPropagators()` into the row.
  - `OutboxRelay.publishPending(): int` (returns number published), scheduled every `platform.outbox.poll-interval` (default `500ms`), batch `platform.outbox.batch-size` (default `100`).
  - Flyway location `classpath:db/platform` must be added by services to `spring.flyway.locations`.
- Table: `outbox_event(event_id uuid pk, event_type text not null, topic text not null, msg_key text not null, payload jsonb not null, traceparent text, occurred_at timestamptz not null, published_at timestamptz, attempts int not null default 0)` + partial index on `occurred_at` where `published_at is null`.

- [ ] **Step 1: Write failing tests** in `OutboxRelayIT`:
  - `publishes_envelope_with_headers`: append one event in a tx, call `publishPending()` → returns 1; consumed record value JSON = `{event_id, event_type, occurred_at, data}`; headers contain `event_id`, `event_type`, and `traceparent` equal to the stored one; row has `published_at` not null.
  - `does_not_republish_published_rows`: second `publishPending()` → 0.
  - `crash_before_mark_resends_same_event_id` (Review Focus 1): make the mark-published step throw once (spy/fault-injection flag on `OutboxRelay`) → next cycle re-sends; both consumed records have the same `event_id`.
  - `concurrent_relays_do_not_double_send` (Review Focus 2): 200 events, two relay instances call `publishPending()` concurrently in a loop until 0 → consumed count == 200, distinct `event_id` == 200.
  - `broker_down_keeps_rows_and_does_not_throw` (Review Focus 3): pause Redpanda container → `publishPending()` returns 0, no exception, `attempts` incremented, `published_at` null; unpause → next call publishes.
  - `append_outside_transaction_fails`: calling `append` without tx → `IllegalTransactionStateException`.
- [ ] **Step 2:** Run `mvn -q -pl libs/java/platform-starter verify` → FAIL.
- [ ] **Step 3:** Implement. Relay algorithm (per cycle, one transaction):
  ```
  rows = SELECT ... WHERE published_at IS NULL ORDER BY occurred_at LIMIT :batch FOR UPDATE SKIP LOCKED
  for row in rows:
      ctx = propagator.extract(Context.root(), {"traceparent": row.traceparent})
      try (scope = ctx.makeCurrent()):           # producer span becomes child of the original request trace
          kafkaTemplate.send(record with headers).get(sendTimeout)   # default 5s
      on failure: UPDATE attempts = attempts + 1; log WARN; stop the batch (keep ordering); return published so far
      UPDATE published_at = now()
  ```
  Producer config: `acks=all`, `enable.idempotence=true`.
- [ ] **Step 4:** Run `mvn -q -pl libs/java/platform-starter verify` → PASS.
- [ ] **Step 5:** Commit `feat(starter): transactional outbox with trace-preserving relay`.

---

### Task 6: order-svc domain + application (pure Java)

**Files:**
- Create: `services/order-svc/pom.xml`; `services/order-svc/src/main/java/dev/platform/order/domain/{Order.java, OrderLine.java, OrderId.java, OrderStatus.java, OrderPlaced.java}`; `.../application/{PlaceOrderUseCase.java, PlaceOrderCommand.java, GetOrderUseCase.java, OrderRepositoryPort.java, OrderEventsPort.java, OrderService.java}`; add module to root `pom.xml`
- Test: `services/order-svc/src/test/java/dev/platform/order/domain/OrderTest.java`, `.../application/OrderServiceTest.java` (in-memory fakes), `.../ArchitectureTest.java`

**Interfaces:**
- Produces:
  - `record OrderLine(String sku, int quantity, BigDecimal unitPrice)` — invariants: sku not blank, quantity ≥ 1, unitPrice ≥ 0 → `IllegalArgumentException` otherwise.
  - `Order.place(OrderId id, String customerId, List<OrderLine> lines, Instant now): Order` — requires ≥1 line, customerId not blank; `total()` = Σ quantity × unitPrice (scale 2, HALF_UP); status `PLACED`; registers domain event `OrderPlaced(orderId, customerId, total, itemCount, occurredAt)`; `pullEvents(): List<OrderPlaced>`.
  - `PlaceOrderUseCase.place(PlaceOrderCommand): Order`; `GetOrderUseCase.get(OrderId): Order` throws `NotFoundException("order", id)`.
  - `OrderRepositoryPort { void save(Order); Optional<Order> findById(OrderId); }`
  - `OrderEventsPort { void publish(OrderPlaced); }`
  - `OrderService` implements both use cases; `place` is `@Transactional` (only Spring annotation allowed in `application`).

- [ ] **Step 1: Write failing tests:** `OrderTest` (total `2×10.00 + 1×5.50 = 25.50`; rejects empty lines; rejects quantity 0; rejects negative price; emits exactly one `OrderPlaced` with `itemCount == 3`); `OrderServiceTest` (place saves and publishes once; get unknown → `NotFoundException`); `ArchitectureTest` (ArchUnit: `..domain..` depends on no `org.springframework..`, `..adapter..`, `..application..`; `..application..` does not depend on `..adapter..`).
- [ ] **Step 2:** Run `mvn -q -pl services/order-svc -am test` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `mvn -q -pl services/order-svc -am verify` → PASS incl. JaCoCo gate.
- [ ] **Step 5:** Commit `feat(order): order domain and use cases`.

---

### Task 7: order-svc adapters (REST, persistence, events) + contracts

**Files:**
- Create: `contracts/openapi/order-svc.yaml`, `contracts/events/order-placed.v1.schema.json`; `services/order-svc/src/main/java/dev/platform/order/OrderApplication.java`; `.../adapter/in/web/{OrderController.java, PlaceOrderRequest.java, OrderResponse.java}`; `.../adapter/out/persistence/{JdbcOrderRepository.java, OrderRow.java, OrderLineRow.java}`; `.../adapter/out/messaging/OutboxOrderEventsAdapter.java`; `services/order-svc/src/main/resources/{application.yml, db/migration/V1__orders.sql}`
- Test: `services/order-svc/src/test/java/dev/platform/order/adapter/in/web/OrderControllerTest.java` (`@WebMvcTest`), `.../OrderFlowIT.java` (Testcontainers Postgres + Redpanda, full app), `.../ContractTest.java`

**Interfaces:**
- Consumes: Task 4 `NotFoundException`/handler, Task 5 `OutboxWriter`/`OutboxEvent`, Task 6 ports.
- Produces (HTTP):
  - `POST /orders` body `{customerId: string, items: [{sku, quantity, unitPrice}]}` → `201`, header `Location: /orders/{id}`, body `OrderResponse {id, customerId, items, total, status, createdAt}`.
  - `GET /orders/{id}` → `200 OrderResponse` | `404` problem.
- Produces (event): topic `orders.order-placed.v1`, key = order id, `event_type = "OrderPlaced"`, `data = {order_id, customer_id, total (string decimal), item_count}`.
- Env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `PORT` (default 8080).

- [ ] **Step 1:** Write `order-svc.yaml` (OpenAPI 3.1, schemas above + `Problem`) and `order-placed.v1.schema.json` (JSON Schema 2020-12, all `data` fields required).
- [ ] **Step 2: Write failing tests:**
  - `OrderControllerTest` (Review Focus 4): valid POST → 201 + `Location`; `items: []` → 400 with `errors[].field == "items"`; `quantity: 0` → 400 field `items[0].quantity`; `unitPrice: -1` → 400; `customerId: " "` → 400; malformed JSON → 400; GET unknown → 404 problem.
  - `OrderFlowIT`: POST then GET returns same order; within 5s a record arrives on `orders.order-placed.v1` whose value validates against `order-placed.v1.schema.json` and `data.order_id` equals created id; `/health/ready` 200.
  - `OrderFlowIT.post_succeeds_when_broker_down` (Review Focus 3): pause Redpanda → POST → 201; unpause → event arrives within 10s.
  - `ContractTest`: every path/method in `order-svc.yaml` is served (request mappings match) and `OrderResponse` JSON for a sample order validates against the spec schema (use `swagger-request-validator` or `networknt json-schema-validator`).
- [ ] **Step 3:** Run `mvn -q -pl services/order-svc -am verify` → FAIL.
- [ ] **Step 4:** Implement. `V1__orders.sql`: `orders(id uuid pk, customer_id text, status text, total numeric(19,2), created_at timestamptz)`, `order_lines(order_id uuid fk, line_no int, sku text, quantity int check>0, unit_price numeric(19,2) check>=0, pk(order_id,line_no))`. `spring.flyway.locations=classpath:db/migration,classpath:db/platform`. Request DTOs use Bean Validation; mapping to domain in controller.
- [ ] **Step 5:** Run `mvn -q -pl services/order-svc -am verify` → PASS.
- [ ] **Step 6:** Commit `feat(order): REST API, persistence and OrderPlaced via outbox`.

---

### Task 8: Container image + light local stack

**Files:**
- Create: `services/order-svc/Dockerfile`, `deploy/local/docker-compose.yml`, `deploy/local/init-db.sql`, `deploy/local/.env.example`, `scripts/smoke-order.sh`

**Interfaces:**
- Produces: image runs as uid 10001, listens on `PORT`, OTel agent at `/otel/opentelemetry-javaagent.jar` enabled via `JAVA_TOOL_OPTIONS`; `OTEL_SERVICE_NAME=order-svc`, `OTEL_LOGS_EXPORTER=none` (logs go to stdout JSON), `OTEL_METRICS_EXPORTER=otlp`, `OTEL_TRACES_EXPORTER=otlp`.
- Compose services: `postgres` (17, creates db/user `orders` via `init-db.sql`), `redpanda` (single node, `--memory 512M --smp 1`), `lgtm` (`grafana/otel-lgtm`, ports 3000 & 4317/4318), `order-svc` (profile `app`, built from Dockerfile). Every container has `mem_limit`; total ≤ 3GB.

- [ ] **Step 1:** Dockerfile: build stage `maven:3.9-eclipse-temurin-21` with `--mount=type=cache` for `~/.m2`, runs `mvn -q -pl services/order-svc -am package -DskipTests`; extract Spring Boot layers (`java -Djarmode=tools -jar app.jar extract --layers`); runtime stage `eclipse-temurin:21-jre` (multi-arch) + OTel agent downloaded at a pinned version with SHA-256 checked; `USER 10001`; `HEALTHCHECK` not used (k8s probes instead).
- [ ] **Step 2:** Build: `docker build -f services/order-svc/Dockerfile -t order-svc:local .` → success; `docker run --rm order-svc:local id -u` → `10001`.
- [ ] **Step 3:** `scripts/smoke-order.sh BASE_URL`: POST sample order, assert 201, GET it, assert 200 and same total; exit non-zero on any mismatch.
- [ ] **Step 4:** Verify: `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build`, wait for `/health/ready`, run `scripts/smoke-order.sh http://localhost:8080` → `OK`; open Grafana `http://localhost:3000` → Explore/Tempo shows a trace for `POST /orders` containing a Kafka producer span from the relay (proves Task 5 context propagation). `docker stats --no-stream` total memory ≤ 3GB. Then `docker compose ... down`.
- [ ] **Step 5:** Commit `feat(order): container image and local compose stack`.

---

### Task 9: GitHub Actions CI + image publish + repo protection

**Files:**
- Create: `.github/workflows/ci.yml` (entry, path filters), `.github/workflows/_java-service.yml` (reusable), `.github/workflows/codeql.yml`, `.github/dependabot.yml`, `.github/pull_request_template.md`

**Interfaces:**
- Produces: required status check named `ci-gate` (single job that depends on all others and fails if any failed/cancelled) — branch protection points only at this name so new services never require protection changes.
- Reusable workflow inputs: `module` (e.g. `services/order-svc`), `image` (e.g. `order-svc`), `publish` (bool, true on push to `main`).

- [ ] **Step 1:** `ci.yml`: triggers `pull_request` and `push` to `main`; `dorny/paths-filter` with filters `starter: libs/java/**`, `order: services/order-svc/**|contracts/**|libs/java/**`, `root: pom.xml|.github/**`; job `order-svc` calls `_java-service.yml` when `order||root`; job `ci-gate` with `if: always()` checking `needs.*.result` ∌ `failure|cancelled`.
- [ ] **Step 2:** `_java-service.yml`: `actions/setup-java` (temurin 21, maven cache) → `mvn -B -pl <module> -am verify` (Spotless, tests, Testcontainers, JaCoCo) → upload surefire/failsafe reports on failure → Trivy fs scan (`severity: CRITICAL`, `exit-code: 1`) → when `publish`: build per-arch on matrix `ubuntu-24.04` (amd64) and `ubuntu-24.04-arm` (arm64) with `docker/build-push-action` pushing by digest, then a merge job creating manifest `ghcr.io/<owner>/<image>:sha-<sha>` and `:main`; permissions `packages: write` only in publish jobs.
- [ ] **Step 3:** `codeql.yml` for `java-kotlin` (build mode `none`), weekly + PR. `dependabot.yml`: `maven` (root), `github-actions`, `docker` (`services/order-svc`), weekly, grouped minor/patch.
- [ ] **Step 4:** Push on branch `ci/bootstrap`, open PR via `ship` skill → all checks green.
  Expected: `gh pr checks` shows `ci-gate` pass.
- [ ] **Step 5:** After merge, verify publish: `gh run list --branch main --limit 1` success; `docker manifest inspect ghcr.io/<owner>/order-svc:main` lists `amd64` and `arm64`. Make the package public: `gh api -X PATCH /user/packages/container/order-svc -f visibility=public` (if API rejects, ask Tuấn to toggle in GitHub UI).
- [ ] **Step 6:** Branch protection on `main`: `gh api -X PUT repos/<owner>/platform/branches/main/protection` with `required_status_checks.contexts=["ci-gate"]`, `strict=true`, `required_pull_request_reviews=null`, `enforce_admins=false`, `allow_force_pushes=false`. Enable repo setting "Allow squash merging" only, "Automatically delete head branches".
- [ ] **Step 7:** Prove the gate: PR that breaks one test → `ci-gate` fails, merge blocked (`gh pr merge` refuses); run `retro` on it as a dry run of the lessons loop; close PR.

---

## Done criteria for Plan 1

- `main` is protected, CI green, `ghcr.io/<owner>/order-svc:sha-…` multi-arch exists.
- Locally: compose stack up, smoke script OK, trace visible in Grafana with relay producer span under the HTTP request trace.
- `docs/lessons/` contains at least the Task 9 Step 7 drill lesson; `CLAUDE.md` + 3 project skills exist.
