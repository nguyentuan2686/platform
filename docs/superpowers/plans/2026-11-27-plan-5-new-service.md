# Plan 5: `new-service` scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One command creates a new stateless HTTP service (Java or Rust) that builds, passes every repo gate, and is wired into CI, Kustomize (e2e + prod, so Argo CD `prod-apps` deploys it), CD tag bumps, local compose, dependabot and contracts.

**Architecture:** Real, compilable skeletons under `templates/{java-service,rust-service}` (plus `templates/k8s`) with placeholder tokens, rendered by `scripts/new-service.sh`. Registration edits shared files only at explicit `# new-service:` marker comments (text insertion, never a YAML round trip, so comments and layout stay intact). A shell test renders both languages into a scratch copy of the repo and checks the result; a CI job builds the rendered services.

**Tech Stack:** bash, sed/awk; Java 21 + Spring Boot (as `services/order-svc`); Rust via `scripts/cargo.sh` (as `services/chart-svc`); Kustomize; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-23-platform-foundation-design.md` §8 ("`new-service`: scaffold một service từ template, đăng ký vào CI, Kustomize, Argo CD và contracts") and §14 step 6. Owner authorised Claude to decide and self-approve (memory: auto-merge-when-ci-green); decisions below are rulings.

## Rulings (design)
- R1 Scope: **stateless HTTP service only** (like chart-svc): `/health/live`, `/health/ready`, one sample endpoint `GET /v1/info` -> `{"service": "<name>"}`, JSON logs with the mandatory fields, OTLP. No database, Kafka or secrets: the script prints a checklist pointing at an existing service for those (YAGNI; each needs judgment: Flyway, secrets.tsv, sealing).
- R2 Tokens: `__SERVICE__` (kebab name, e.g. `demo-svc`), `__JAVA_PKG__` (`demosvc`: name without `-`, Java package `dev.platform.demosvc`), `__CRATE__` (`demo_svc`), `__PORT__`, `__CLASS__` (`DemoSvc`, for the Spring Boot application class). File and directory names may carry tokens too.
- R3 Name rule: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, 3–30 chars, not an existing `services/<name>`, not `web`. Port: 8086–8999 and not used anywhere in `deploy/local/docker-compose.yml` or `deploy/k8s/base/*/` (container port inside k8s may be the same number; uniqueness matters for compose host ports).
- R4 Registration targets, each via a marker comment added in this plan (one per insertion point, e.g. `# new-service: ci-filters`): root `pom.xml` `<modules>` (Java; XML comment marker) or `Cargo.toml` `members` (Rust; rewrite of the single `members = [...]` line is acceptable if a marker is impractical: document which); `.github/workflows/ci.yml` (changes output, paths filter, job, `ci-gate` needs); `deploy/k8s/base/<name>/` (rendered from `templates/k8s`); `deploy/k8s/overlays/e2e/kustomization.yaml` and `deploy/k8s/overlays/prod/apps/kustomization.yaml` (resources + `images` entry with `newTag: main`); `scripts/cd-bump.sh` `SERVICES`; `.github/dependabot.yml` docker entry; `deploy/local/docker-compose.yml` service under profile `app`; `contracts/openapi/<name>.yaml` stub. Find every other place that lists all services (`grep -rn chart-svc` over `.github scripts deploy` excluding fixtures) and either register there too or record why not in the script's checklist output.
- R5 Idempotent and safe: refuse when any target already mentions the service; render into a temp dir and only move into place after every check passes; on any failure leave the tree unchanged (`git status` clean apart from nothing). Prints what it changed and the manual follow-ups (GHCR package must be made public after the first main push, like the others; DB/Kafka/secrets checklist; smoke script).
- R6 Tools: the script needs bash, sed, awk, git only (works in Git Bash on the dev machine and in CI); no yq/python.
- R7 Verification: `scripts/test/new-service.test.sh` (picked up by CI job `ops-scripts`) renders a Java and a Rust service into a scratch copy of the tracked tree and asserts: files rendered with no leftover `__TOKEN__`; every registration present exactly once; second run refused; bad name/port/used port refused and tree untouched. New CI job `scaffold` (in `ci.yml`, only when `templates/**`, `scripts/new-service.sh` or its test change, or `root`) renders `demo-java` (java) and `demo-rust` (rust) into the checkout and runs: `mvn -q -pl services/demo-java -am verify`, `scripts/cargo.sh`-equivalent `cargo build/test -p demo-rust` + `fmt --check` + `clippy -D warnings`, `docker build` of both images, and `bash scripts/k8s-validate.sh` (kustomize + policy with the new services). It is in `ci-gate` needs.
- R8 Skill `.claude/skills/new-service/SKILL.md` (when to use, the command, the checklist), and CLAUDE.md: repo-map line for `templates/` and a Commands line for `scripts/new-service.sh`.

## Global Constraints
- CLAUDE.md conventions apply to the templates: Java 21, package `dev.platform.<pkg>`, hexagonal `domain/application/adapter`, ArchUnit domain purity, constructor injection, records, RFC 9457 errors, >= 80% line coverage of domain+application, `/health/live` + `/health/ready`, JSON logs `timestamp, level, service, trace_id, span_id, message`; Rust per `.claude/skills/rust-service-conventions/SKILL.md` (platform-core, domain purity test).
- Images non-root, multi-arch, same Dockerfile shape as `services/order-svc/Dockerfile` / `services/chart-svc/Dockerfile`.
- k8s base must pass `scripts/k8s-validate.sh` policies (resources, probes, non-root, no latest tag) exactly like `deploy/k8s/base/chart-svc`.
- Shell: `set -euo pipefail`, no producer piped into an early-closing reader, shellcheck clean.
- Never commit generated demo services: the CI job renders them in its own checkout only.
- Workflow `run:` steps follow the pinned-SHA action rule (`scripts/check-action-pins.sh`).

## Review Focus
1. Running the script twice, or for a name that exists, must not half-edit files.
2. A Windows checkout (CRLF in shared files) must not break marker insertion.
3. The rendered Java service must pass the real `mvn verify` gates (Spotless, coverage, ArchUnit), not only compile.
4. The rendered ci.yml must stay valid for actionlint and keep `ci-gate` failing when the new job fails.
5. Port collisions with existing compose host ports.

---

### Task 1: Templates + k8s template + markers
Files: `templates/java-service/**`, `templates/rust-service/**`, `templates/k8s/**`, marker comments in the shared files of R4.
- [ ] Copy the minimal shape of order-svc (Java) and chart-svc (Rust) into templates with tokens; strip domain logic to the sample endpoint; keep their tests' style (unit, ArchUnit/domain purity, one HTTP test).
- [ ] Add the `# new-service:` markers (no behaviour change; `k8s-validate`, actionlint still pass).
- [ ] Commit.

### Task 2: `scripts/new-service.sh` + `scripts/test/new-service.test.sh`
- [ ] Write the failing test (cases in R7), run it, see it fail.
- [ ] Implement the script per R2–R6. Run the test and shellcheck until green.
- [ ] Commit.

### Task 3: CI job `scaffold`, skill, CLAUDE.md
- [ ] Add the job per R7 and to `ci-gate` needs; run actionlint and `scripts/check-action-pins.sh`.
- [ ] Locally (weak machine, one at a time): render a Java service in a scratch worktree and run `mvn -q -pl services/<name> -am verify`; render a Rust one and run `scripts/cargo.sh test -p <crate>`; delete the scratch worktree.
- [ ] Skill + CLAUDE.md lines. Commit.
