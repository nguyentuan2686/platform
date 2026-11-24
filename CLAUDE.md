# CLAUDE.md - working rules for this repo

Polyglot microservices platform (Java/Spring Boot + Rust), monorepo. Owner: Tuan. Builder: Claude.
Design spec: `docs/superpowers/specs/`. Plans: `docs/superpowers/plans/`. ADRs: `docs/adr/`.

## Workflow
brainstorm -> spec -> plan -> TDD (red, green, refactor) -> review -> verify (run build/tests, show evidence) -> PR -> CI -> merge -> verify-deploy -> retro.
- Trunk-based: short branch -> PR -> CI green -> Claude squash-merges. No human review required when CI is green.
- Any failure (CI, unexpected test fail, prod bug, failed deploy) triggers a retro: write a lesson (see Lessons loop).
- Never claim "done" without running the verification command and reading its output.

## Repo map
```
CLAUDE.md, .claude/{settings.json,skills/}
Cargo.toml, rust-toolchain.toml, deny.toml, tools/rust-dev/Dockerfile   # Rust workspace + dev image
contracts/{openapi/<svc>.yaml, events/<event>.schema.json}
services/{order-svc (Java), notify-svc (Rust), chart-svc (Rust)}
libs/{java/platform-starter, rust/platform-core}
templates/{java-service, rust-service}
deploy/{local/docker-compose.yml, k8s/{base,overlays/prod,platform}, argocd}
infra/bootstrap/   # idempotent host setup: k3s + argocd + sealed-secrets
scripts/           # cargo.sh/cargo.ps1 (Docker Rust), new-service, verify-deploy, public-url
docs/{adr, superpowers/{specs,plans}, runbooks, lessons}
.github/{workflows, dependabot.yml}
```

## Java conventions
- Java 21; base package and groupId `dev.platform`.
- Hexagonal packages per service: `domain` / `application` / `adapter`. `domain` has no Spring (or other framework) imports; enforce with ArchUnit.
- Constructor injection only; no `@Autowired` on fields.
- Records for DTOs and value objects.
- HTTP errors via RFC 9457 Problem Details; never stack traces or exception class names in responses.
- Coverage gate: >= 80% line coverage for `domain` and `application`.
- Database per service; schema changes via Flyway migrations.

## Logging / observability contract
- Every HTTP service exposes `/health/live` and `/health/ready`.
- Logs: JSON on stdout, one object per line, mandatory fields `timestamp, level, service, trace_id, span_id, message` (exact names; Rust emits the same).
- HTTP errors: `application/problem+json` with extra property `trace_id`.
- Config via env vars (12-factor); no plaintext secrets in Git.
- Events: JSON envelope `{event_id, event_type, occurred_at, data}`; W3C `traceparent` Kafka header; consumers idempotent on `event_id`.
- Event evolution rule: additive changes (new optional `data` fields) stay in v1, so event schemas leave `data` open and consumers ignore unknown fields; breaking changes (remove/rename/retype a field, change meaning) go to a new topic `<name>.v2` with its own schema, published alongside v1 until consumers migrate.
- Images: non-root, multi-arch `linux/amd64` + `linux/arm64`, tag `sha-<commit>`, pushed to `ghcr.io/<owner>/<service>`.
- Dev machine is weak (2 cores / 12GB): local stack <= ~3GB; heavy verification runs in CI.

## Rust conventions
See `.claude/skills/rust-service-conventions/SKILL.md`. No native Rust: all cargo via `scripts/cargo.sh` (Docker). Toolchain pinned in `rust-toolchain.toml`.

## Commands
- `mvn -q verify` - build, test, Spotless check, coverage gate
- `mvn spotless:apply` - auto-format
- `docker compose -f deploy/local/docker-compose.yml up -d` - local Postgres + Redpanda + otel-lgtm
- `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build` - also order-svc (:8080), notify-svc (:8081) and chart-svc (:8082, stateless)  (if a `pgdata` volume from before Plan 2 exists, run `down -v` once so `init-db.sql` creates the notify db)
- `scripts/smoke-flow.sh http://localhost:8080 http://localhost:8081` - end-to-end: POST /orders -> notification appears
- `scripts/smoke-chart.sh http://localhost:8082` - POST /tuvi/chart happy path: 12 palaces, lunar.month 2
- `scripts/cargo.sh <args>` (PowerShell: `scripts/cargo.ps1`) - run cargo in the dockerized Rust toolchain: `fmt --check`, `clippy --all-targets -- -D warnings`, `deny check`, `test`

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
