---
name: rust-service-conventions
description: Use when creating or modifying a Rust service or library in this repo (platform-core, notify-svc), or reviewing such a change; covers module layout, domain purity test, error strategy, logging, test pyramid, naming, running cargo via Docker.
---

# Rust service conventions

Stable toolchain pinned in `rust-toolchain.toml`; Cargo workspace at repo root. Full rules also in `CLAUDE.md`.
There is NO native Rust on the dev machine: every cargo command goes through `scripts/cargo.sh` (Docker image `platform-rust-dev:<toolchain>`).

## Running things
```
scripts/cargo.sh test --workspace                      # unit + integration (testcontainers via mounted docker socket)
scripts/cargo.sh fmt --all                             # format (use `fmt --check` to verify)
scripts/cargo.sh clippy --workspace --all-targets -- -D warnings
scripts/cargo.sh deny check                            # licenses, advisories, sources
scripts/cargo.sh llvm-cov --workspace --fail-under-lines 80 --ignore-filename-regex '(main\.rs|/tests/)'   # coverage gate
```
First run builds the image; cargo registry/git are named volumes, build output goes to `target/docker/`. On Windows use Git Bash (`scripts/cargo.sh`) or `scripts\cargo.ps1`.

## Module layout (hexagonal, mirrors Java)
```
src/domain          # entities, value objects, domain errors. Pure: no tokio/axum/sqlx/rdkafka/serde_json-IO
src/application     # use cases, *Port traits (owned here), orchestration
src/adapters/http        # axum handlers, request/response DTOs
src/adapters/db          # sqlx repositories (*Adapter implementing *Port)
src/adapters/messaging   # rdkafka consumers/producers
src/main.rs         # wiring only: config, adapters, server start
```
Dependencies point inward only: adapters -> application -> domain. Services are `lib` (all logic) + thin `bin`.

## Domain purity test (must exist per service)
A test in `tests/architecture.rs` walks `src/domain/**/*.rs` and fails if any line has `use` of a forbidden path: `tokio`, `axum`, `sqlx`, `rdkafka`, `tower`, `hyper`, `reqwest`, `opentelemetry`, `crate::adapters`. Also: `src/application/**` must not `use crate::adapters`.

## Errors
- `thiserror` enums in `domain` and `application` (typed, matchable); no `anyhow` there.
- `anyhow` only in `main.rs` and adapter wiring/startup code.
- No `unwrap()`/`expect()` in non-test code except provably infallible, with a comment. Map errors to HTTP at the adapter edge.
- HTTP errors: RFC 9457 `application/problem+json`, `type = "urn:problem:<slug>"`, always `trace_id`; 500 detail exactly `"Unexpected error"`; never leak internals (same slugs as the Java table).

## Logging
- JSON on stdout via `platform_core::telemetry`; mandatory fields `timestamp, level, service, trace_id, span_id, message`; extra fields under `ctx`; errors under `error`.
- Error logs: `error!(error = %e, error_type = "DbUnavailable", "...")`; `error_type` becomes `error.type` (default `"error"`), never derived from Debug.
- Log at boundaries (request result, event consumed/skipped, outbound failure), not inside domain logic.
- Never log payloads or PII (names, emails, phones, addresses, tokens); ids only. No `println!`/`dbg!`.

## Naming
- `*Port` traits (application-owned), `*Adapter` structs (adapters), `*UseCase`/service structs in application.
- Config from env only (12-factor), parsed once in `main`.

## Test pyramid (test first: red -> green -> refactor)
1. Domain unit tests (`#[cfg(test)]`), no async runtime.
2. Application tests with in-memory fakes of ports (hand-written fakes over mock frameworks).
3. Adapter tests: HTTP via `tower::ServiceExt::oneshot`; db/messaging against real Postgres/Kafka (testcontainers), never SQLite/mocks.
4. Integration tests in `tests/` with testcontainers (Postgres + Redpanda): few, happy path plus key failures.
- Gate: >= 80% line coverage (`llvm-cov`) excluding `main.rs` and test fixtures.

## Lints / quality
- `[workspace.lints]` in root `Cargo.toml` (clippy pedantic as warn, `unsafe_code = forbid`); members set `[lints] workspace = true`.
- All shared dependency versions live in `[workspace.dependencies]`; members use `dep.workspace = true`.
- `cargo deny check` must pass; new licenses need a deliberate `deny.toml` edit.
- DB: schema changes only via sqlx migrations; never edit applied ones.

## Review checklist
- [ ] Domain/application free of adapter and framework imports; architecture test green
- [ ] Test written first; pyramid layers appropriate; coverage gate met
- [ ] Errors typed (`thiserror`); Problem Details with `trace_id`, no internals leaked
- [ ] No PII/payloads in logs; no secrets in code or config
- [ ] Migration added for schema changes; contract (`contracts/`) updated if API/event changed
- [ ] `fmt --check`, `clippy -D warnings`, `deny check`, `test` output read and green
