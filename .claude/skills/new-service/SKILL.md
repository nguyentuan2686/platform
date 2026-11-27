---
name: new-service
description: Use when adding a new backend service (Java/Spring Boot or Rust) to this repo; covers the scripts/new-service.sh command, what it generates and registers, and the manual follow-ups (database, Kafka, secrets, smoke test, GHCR visibility).
---

# New service

Never hand-copy an existing service: `scripts/new-service.sh` renders a stateless HTTP skeleton that already passes
every gate and registers it everywhere the platform lists services. Then grow it (TDD) with the conventions skill of
its language (`java-service-conventions` / `rust-service-conventions`).

## Command
```
scripts/new-service.sh --lang java|rust --name <name> --port <port>
```
- `<name>`: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, 3-30 chars, not an existing service, CI job/filter key, compose
  service or reserved name (`web`, `postgres`, ...), not a Java/Rust keyword after removing `-` / turning it into `_`.
- `<port>`: 8086-8999, not used in `deploy/local/docker-compose.yml` or `deploy/k8s/base/*/` (it is the container port
  and the local compose host port).
- Bash, sed, awk only (Git Bash on the dev machine, CI). Refused runs change nothing; a second run is refused.

## What it does
- Renders `templates/java-service` or `templates/rust-service` -> `services/<name>`, `templates/k8s` ->
  `deploy/k8s/base/<name>`, `templates/contracts/openapi.yaml` -> `contracts/openapi/<name>.yaml`. Tokens:
  `__SERVICE__` (name), `__JAVA_PKG__` (name without `-`), `__CRATE__` (`-` -> `_`), `__PORT__`, `__CLASS__`
  (PascalCase), `__ENV__` (upper crate, compose port variable).
- The skeleton: `GET /v1/info` -> `{"service": "<name>"}`, `/health/live`, `/health/ready`, problem+json errors with
  `trace_id`, JSON logs, OTLP; hexagonal layers, ArchUnit / domain-purity test, contract test against the OpenAPI stub.
- Inserts registration snippets (`templates/registration/`) at the `new-service: <point>` marker comments: root
  `pom.xml` modules and every Java Dockerfile's pom list (Java) or `Cargo.toml` members + the `Cargo.lock` entry
  (Rust); `ci.yml` (changes output, paths filter, job, `ci-gate` needs); `_k8s-e2e.yml` (image build + kind load);
  `cd.yml` and `scripts/cd-bump.sh` SERVICES; `dependabot.yml`; compose (profile `app`); e2e and prod Kustomize
  (resource + `images` entry, prod `newTag: main` for CD).

## After running
1. Verify: Java `mvn -q -pl services/<name> -am verify`; Rust `scripts/cargo.sh fmt --check`,
   `scripts/cargo.sh clippy -p <name> --all-targets --locked -- -D warnings`, `scripts/cargo.sh test -p <name> --locked`.
2. `git diff --stat`, replace the sample endpoint (code + contract), commit, PR. CI job `scaffold` only proves the
   templates; the new service's own job runs from its paths filter.
3. After the first push to main: make the GHCR package public (`docs/runbooks/go-live.md`), else prod cannot pull it.

## Not scaffolded (judgment needed; copy from the named service)
- Database: Flyway (order-svc) or sqlx (notify-svc), `deploy/local/init-db.sql`, `wait-for-postgres` component.
- Kafka: producer-declared topics (`NewTopic` bean), idempotent consumers; secrets: `secrets.tsv` + sealing.
- Public route (prod ingress + its policy), smoke script + `scripts/k8s-e2e.sh` port-forward/run, the RED job list in
  `scripts/e2e-observability.sh`, and for Java the startup-probe image list in `scripts/k8s-validate.sh`.

## Changing the templates
Edit `templates/**`, keep lines that carry a token short (formatters must not wrap them differently per name length:
use a constant, not a repeated literal), run `bash scripts/test/new-service.test.sh`; CI job `scaffold` renders
`demo-java` / `demo-rust` and runs every gate. Never commit a rendered demo service.
