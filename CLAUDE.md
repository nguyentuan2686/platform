# CLAUDE.md - working rules for this repo

Polyglot microservices platform (Java/Spring Boot + Rust), monorepo. Owner: Tuan. Builder: Claude.
Design spec: `docs/superpowers/specs/`. Plans: `docs/superpowers/plans/`. ADRs: `docs/adr/`.

## Workflow
brainstorm -> spec -> plan -> TDD (red, green, refactor) -> review -> verify (run build/tests, show evidence) -> PR -> CI -> merge -> verify-deploy -> retro.
- Trunk-based: short branch -> PR -> CI green -> Claude squash-merges. No human review required when CI is green.
- Any failure (CI, unexpected test fail, prod bug, failed deploy) triggers a retro: write a lesson (see Lessons loop).
- Never claim "done" without running the verification command and reading its output.
- Before each commit read `git diff --stat`: no deletions or files outside the task's scope. Never call a failure "pre-existing" without reproducing it on the base commit.
- CI triage: read the failing log first. HTTP 429/5xx or "could not transfer artifact" from a package registry, or a `next/font` download error, is an external flake: `gh run rerun --failed` and record a lesson, no code change.

## Repo map
```
CLAUDE.md, .claude/{settings.json,skills/}
Cargo.toml, rust-toolchain.toml, deny.toml, tools/rust-dev/Dockerfile   # Rust workspace + dev image
contracts/{openapi/<svc>.yaml, events/<event>.schema.json, knowledge/}
knowledge/tuvi/   # reading-tuvi knowledge base (jsonl), imported at startup
services/{order-svc (Java), notify-svc (Rust), chart-svc (Rust), reading-tuvi (Java), identity-svc (Java), payment-svc (Java), web (Next.js)}
libs/{java/platform-starter, rust/platform-core}
templates/{java-service, rust-service}
deploy/{local/docker-compose.yml, k8s/{base,overlays/{prod,e2e},platform}, argocd}   # k8s: Kustomize; overlays/e2e = CI kind test values
infra/bootstrap/   # idempotent host setup: k3s + argocd + sealed-secrets
scripts/           # cargo.sh/cargo.ps1 (Docker Rust), k8s-validate.sh, k8s-e2e.sh + e2e-observability.sh (CI kind only), smoke-*, new-service, verify-deploy, public-url
docs/{adr, superpowers/{specs,plans}, runbooks, lessons}
.github/{workflows, dependabot.yml}
```

## Java conventions
- Java 21; base package and groupId `dev.platform`.
- Hexagonal packages per service: `domain` / `application` / `adapter`. `domain` has no Spring (or other framework) imports; enforce with ArchUnit.
- Constructor injection only; no `@Autowired` on fields.
- Records for DTOs and value objects.
- HTTP errors via RFC 9457 Problem Details; never stack traces or exception class names in responses.
- Never an empty or log-less catch (Java and web): a catch rethrows, maps an expected upstream status, or hands the error to a shared handler that logs or rethrows (web: `degradeOrThrow` / `logRefusalOrThrow` in `lib/degrade.ts`, `upstreamFailure`). A deliberate swallow (browser code, parsing untrusted input) carries a `catch-ok: <reason>` comment. Web hard check: `src/catchPolicy.test.ts` + ESLint `no-empty`.
- Coverage gate: >= 80% line coverage for `domain` and `application`.
- Database per service; schema changes via Flyway migrations.

## Logging / observability contract
- Every HTTP service exposes `/health/live` and `/health/ready`.
- Logs: JSON on stdout, one object per line, mandatory fields `timestamp, level, service, trace_id, span_id, message` (exact names; Rust emits the same).
- HTTP errors: `application/problem+json` with extra property `trace_id`.
- Config via env vars (12-factor); no plaintext secrets in Git.
- Events: JSON envelope `{event_id, event_type, occurred_at, data}`; W3C `traceparent` Kafka header; consumers idempotent on `event_id`. Topics are declared at startup by their producer (Java: a `NewTopic` bean; Spring consumers too), never left to broker auto-create (off in the k8s Redpanda).
- Event evolution rule: additive changes (new optional `data` fields) stay in v1, so event schemas leave `data` open and consumers ignore unknown fields; breaking changes (remove/rename/retype a field, change meaning) go to a new topic `<name>.v2` with its own schema, published alongside v1 until consumers migrate.
- Images: non-root, multi-arch `linux/amd64` + `linux/arm64`, tag `sha-<commit>`, pushed to `ghcr.io/<owner>/<service>`.
- Dev machine is weak (2 cores / 12GB): local stack <= ~3GB (full `--profile app` is ~2.75GB; identity-svc and payment-svc need `mem_limit: 512m`, the JVM off-heap alone is ~170MiB); heavy verification runs in CI.

## Rust conventions
See `.claude/skills/rust-service-conventions/SKILL.md`. No native Rust: all cargo via `scripts/cargo.sh` (Docker). Toolchain pinned in `rust-toolchain.toml`.

## Commands
- `mvn -q verify` - build, test, Spotless check, coverage gate
- `mvn spotless:apply` - auto-format
- `docker compose -f deploy/local/docker-compose.yml up -d` - local Postgres + Redpanda + otel-lgtm
- `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build` - also order-svc (:8080), notify-svc (:8081) and chart-svc (:8082, stateless) and reading-tuvi (:8083; needs the `reading_tuvi` db), identity-svc (:8084), payment-svc (:8085) and Mailpit (SMTP :1025, UI :8025) (if a `pgdata` volume from before F1.3 exists, run `down -v` once so `init-db.sql` creates the notify, reading_tuvi, identity and payment dbs; test secrets in `deploy/local/.env.example`)
- `scripts/smoke-flow.sh http://localhost:8080 http://localhost:8081` - end-to-end: POST /orders -> notification appears
- `scripts/smoke-chart.sh http://localhost:8082` - POST /tuvi/chart happy path: 12 palaces, lunar.month 2
- `scripts/smoke-reading.sh http://localhost:8083` - basic reading (17 sections, last `disclaimer`) + today (4 areas) via reading-tuvi -> chart-svc
- `scripts/smoke-qa.sh` - paid/free tử vi Q&A end to end on the fake provider (needs `postgres redpanda mailpit chart-svc identity-svc reading-tuvi`; bring up with `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build --no-deps <those>`; each run uses a fresh birth time because the free quota is per chart)
- `scripts/smoke-identity-payment.sh [IDENTITY_URL] [PAYMENT_URL] [MAILPIT_URL]` - guest -> magic link (read from Mailpit) -> login -> CREDIT_5 order -> simulated SePay transfer (`scripts/sepay-simulate.sh`) -> credits 6, duplicate delivery changes nothing; low-memory subset: `docker compose -f deploy/local/docker-compose.yml --profile app up -d postgres redpanda mailpit identity-svc payment-svc`
- `scripts/smoke-web.sh http://localhost:3001` - web: /health/ready 200, /vi contains "Lão Thiên Cơ", /vi/kien-thuc and /robots.txt 200, prints `OK`; web e2e (Playwright + stub upstreams, in CI job `e2e`): `npm run e2e` in services/web. Full-stack e2e (local only, never CI; real stack, Mailpit magic link, fake LLM, dev payment simulator): bring up `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build --no-deps postgres redpanda mailpit chart-svc reading-tuvi identity-svc payment-svc web`, then `docker run --rm --network host -v "$PWD/services/web:/w" -w /w mcr.microsoft.com/playwright:v1.65.1-noble sh -c "npm ci && npx playwright test -c playwright.stack.config.ts"`, then `down -v`. Web env and prod rules: `docs/runbooks/web.md`
- `docker run --rm -v "$PWD/services/web:/app" -v "$PWD/knowledge/tuvi:/knowledge/tuvi:ro" -e KNOWLEDGE_DIR=/knowledge/tuvi -w /app node:22-alpine sh -c "npm ci && npm run lint && npm run typecheck && npm test && npm run build && npm run check:bundle"` - web (Next.js) gates; no native Node. Local: web on http://localhost:3001 (`--profile app`)
- `scripts/cargo.sh <args>` (PowerShell: `scripts/cargo.ps1`) - run cargo in the dockerized Rust toolchain: `fmt --check`, `clippy --all-targets -- -D warnings`, `deny check`, `test`
- `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh` - k8s manifests: kustomize build + kubeconform + policy checks + dashboards (CI job `k8s-manifests`; `--self-test` checks the fixtures)
- kind e2e of the full stack (deploy `overlays/e2e`, every smoke script, then `scripts/e2e-observability.sh`: cross-service trace, RED/consumer/outbox/consumer-lag metrics, logs with trace_id, dashboards) runs only in CI job `k8s-e2e` (`.github/workflows/_k8s-e2e.yml`: every push to main, PRs touching `deploy/k8s/**`, `deploy/local/**`, `scripts/**` or the e2e workflow); never run kind locally. On failure read the `k8s-e2e-diagnostics` artifact first

## Testing
- A test must wait on the observable end state with a deadline, never on "an operation returned 0/empty once" or a fixed sleep. Tight timeouts in a test must not turn a slow first call (fresh Kafka topic, container warm-up) into a failure. Loops end on state, not on a return value. A flake that recurs gets a fix, not a rerun.
- Shell under `set -e -o pipefail` (scripts and every workflow `run:`): never pipe a producer into an early-closing reader (`head`, `grep -q`, `grep -m`, `awk '...; exit'`: SIGPIPE, exit 141); capture first, then `grep -q ... <<<"$x"`. A probe inside a poll loop that may fail ends in `|| <fallback>`, or the first "not yet" kills the step.

## Lessons loop
Record in `docs/lessons/YYYY-MM-DD-<slug>.md` (template in `docs/lessons/README.md`). Escalation ladder:
1. First occurrence: write the lesson file only.
2. Second occurrence: add a rule to this file (or the relevant skill).
3. If automatable: make it a hard check (lint rule, ArchUnit test, CI step, hook).

Review `docs/lessons/` after each large feature; merge or prune. Personal working preferences of the owner go to Claude memory, not here.

## Never
- Commit secrets (tokens, keys, `.env`, plaintext Kubernetes Secrets; use Sealed Secrets).
- Push to `main` directly (exception: the CD bot, only under `deploy/k8s/overlays/`).
- Skip hooks (`--no-verify`) or bypass signing/CI.
- Write the owner's email or phone anywhere in the repo.
