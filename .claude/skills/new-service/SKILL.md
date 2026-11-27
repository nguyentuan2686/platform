---
name: new-service
description: Use when adding a new backend service (Java/Spring Boot or Rust) to this repo; covers the scripts/new-service.sh command, what it generates and registers, the separate --prod step, and the manual follow-ups (database, Kafka, secrets, smoke test, GHCR visibility).
---

# New service

Never hand-copy an existing service: `scripts/new-service.sh` renders a stateless HTTP skeleton that already passes
every gate and registers it everywhere the platform lists services, except prod. Then grow it (TDD) with the
conventions skill of its language (`java-service-conventions` / `rust-service-conventions`).

## Commands
```
scripts/new-service.sh --lang java|rust --name <name> --port <port>   # PR 1: scaffold + CI/e2e/compose
scripts/new-service.sh --prod <name> [--skip-pull-check]              # PR 2: prod overlay + CD, once pullable
```
- `<name>`: `^[a-z][a-z0-9]*(-[a-z0-9]+)+$` (at least one dash), 3-30 chars; not an existing service, CI job/filter
  key, compose service, reserved name, Java package `dev.platform.<name without dashes>` (no Java keyword) or Cargo
  package/crate (`-` and `_` count as equal). The dash rule also keeps Kubernetes service-link env names
  (`server` -> `SERVER_PORT`) and Cargo/Windows reserved names (`build`, `con`, `nul`) out.
- `<port>`: 8086-8999, decimal without a leading zero, not used in `deploy/local/docker-compose.yml` or
  `deploy/k8s/base/*/*.yaml` (it is the container port and the local compose host port).
- `zz-java`, `demo-rust`, ports 8998/8999: reserved for CI job `scaffold` (`NEW_SERVICE_CI_SCAFFOLD=1`).
- Bash, sed, awk only (Git Bash on the dev machine, CI); `--prod` also needs docker for its pull check. Refused runs
  change nothing, a failed write is rolled back, a second run is refused.

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
  `dependabot.yml`; compose (profile `app`); e2e Kustomize (resource + `images` entry).
- `--prod <name>`: checks `ghcr.io/nguyentuan2686/<name>:main` is pullable anonymously (`docker manifest inspect` with
  an empty `DOCKER_CONFIG`), then adds exactly the prod path: `deploy/k8s/overlays/prod/apps` (resource + image
  `newTag: main`), `cd.yml` and `scripts/cd-bump.sh` SERVICES.

## After running
1. Verify: Java `mvn -q -pl services/<name> -am verify`; Rust `scripts/cargo.sh fmt --check`,
   `scripts/cargo.sh clippy -p <name> --all-targets --locked -- -D warnings`, `scripts/cargo.sh test -p <name> --locked`.
2. `git diff --stat`, replace the sample endpoint (code + contract), commit, PR. CI job `scaffold` only proves the
   templates; the new service's own job runs from its paths filter. The service is not in prod yet.
3. After that PR is merged and its ci run published the image: GHCR package -> Package settings -> Change
   visibility -> Public (`docs/runbooks/go-live.md`). Prod has no pull secret.
4. Follow-up PR: `scripts/new-service.sh --prod <name>`. Why separate: registering prod before the image is public
   puts `prod-apps` into ImagePullBackOff, `verify-deploy` fails and CD reverts every bump of that commit.

## Not scaffolded (judgment needed; copy from the named service)
- Database: Flyway (order-svc) or sqlx (notify-svc), `deploy/local/init-db.sql`, `wait-for-postgres` component.
- Kafka: producer-declared topics (`NewTopic` bean), idempotent consumers; secrets: `secrets.tsv` + sealing.
- Public route (prod ingress + its policy), smoke script + `scripts/k8s-e2e.sh` port-forward/run, the RED job list in
  `scripts/e2e-observability.sh`, and for Java the startup-probe image list in `scripts/k8s-validate.sh`.
- Local memory: each service joins compose profile `app` (Java 512m); update the local budget line in CLAUDE.md.

## Changing the templates
Edit `templates/**`, keep lines that carry a token free of formatter dependence: no repeated name literal (use a
constant), Rust `use <crate>::` in its own import group, Java: no import of another `dev.platform.*` package (fully
qualify it), since google-java-format sorts it around the service's own package by name. Version pins in the
template Dockerfiles must equal `services/order-svc/Dockerfile` / `services/chart-svc/Dockerfile` (the test checks).
Run `bash scripts/test/new-service.test.sh`; CI job `scaffold` renders `zz-java` / `demo-rust` and runs every gate.
Never commit a rendered demo service.
