# Plan 4a — GitOps and ops, host-agnostic, verified in CI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build everything that spec phases 5–6 need and that works without a prod host, a domain, a Telegram bot, a GitHub App, Tailscale or object storage. That covers host bootstrap, Argo CD app-of-apps, Sealed Secrets tooling, the cloudflared tunnel, the CD tag bump, verify-deploy with auto-rollback, alerts, retention, backup/restore and the external heartbeat. All of it is verified on GitHub runners. Each external dependency is mocked in CI, or its job/step is skipped with a `::notice::` when its secret is absent. Once Tuấn supplies the host and secrets, going live is configuration only (`docs/runbooks/go-live.md`).

**Architecture:** `infra/bootstrap/bootstrap.sh` turns any Ubuntu 24.04 machine (x86 or ARM) into a k3s node with the firewall closed, Argo CD and the Sealed Secrets controller. It then applies one root Argo CD Application. The root app syncs four child apps from `deploy/argocd/apps/`, each pointing at a slice of the prod overlay: `sealed`, then `platform`, then `tunnel` and `apps`. The prod overlay is split into these slices so each object is owned by exactly one app. CD is a `workflow_run` on green `ci` on `main`. It bumps only the images that this commit actually published, commits with a GitHub App token, then verify-deploy reaches the cluster over Tailscale (`kubectl` + port-forward to Traefik), checks Argo health and runs a smoke test. On failure it reverts the bump and opens an issue. Alerts, retention, backup and heartbeat are plain manifests in `deploy/k8s/platform/`, so the existing kind e2e exercises them. A new CI job `bootstrap` runs the real bootstrap twice on `ubuntu-24.04` and `ubuntu-24.04-arm`, and Argo CD syncs the PR's own commit.

**Tech Stack:** k3s (v1.33 line, matching the kubectl v1.33.12 already pinned in `_k8s-e2e.yml`), Argo CD (install manifest), Bitnami Sealed Secrets controller + `kubeseal`, `cloudflare/cloudflared`, Tailscale (static tarball), ufw, Kustomize `replacements`/components, Grafana unified alerting provisioning (inside `grafana/otel-lgtm:0.41.1`), OTel Collector contrib receivers `k8s_cluster` + `hostmetrics`, `promtool` (`prom/prometheus` image), `postgres:17-alpine`, `rclone/rclone`, `minio/minio` (e2e only), `curlimages/curl`, `actions/create-github-app-token`, `tailscale/github-action`, shellcheck, actionlint. Use the latest stable version of each at implementation time, pinned by exact version plus sha256 (binaries/manifests) or digest (images) or commit SHA (actions).

**Spec:** `docs/superpowers/specs/2026-11-23-platform-foundation-design.md` (§5, §6, §7 CD 1–4, §8 admin network, §10 alerts/heartbeat/retention, §11, §12, §13, §14 items 5–6, §16.1, §16.3, §16.4). Prior plan: `docs/superpowers/plans/2026-11-26-plan-3-k8s-observability.md` (its layout, validator, `k8s-e2e` job and Global Constraints still apply). **Deferred (Plan 4b or later):** the `new-service` and `observe` skills (§8, §14.6); JVM/node dashboard panels; a Grafana read-only token for Claude; splitting otel-lgtm into separate components; multi-node concerns (moving the `k8s_cluster` receiver out of the DaemonSet). The Plan 3 follow-ups that are folded in are in Task 2. None was ruled out.

## Global Constraints

- Dev machine (2 cores / 12 GB) never runs kind, k3s, Argo CD or bootstrap. Local checks are limited to `shellcheck`, `actionlint`, `scripts/k8s-validate.sh`, `promtool` and `scripts/test/*.test.sh` in Docker. Everything else runs in CI.
- Every downloaded tool is pinned. Binaries, tarballs and manifests are pinned by exact version + sha256, verified before use (`sha256sum -c`). Images are pinned by `tag@sha256:<digest>` in every manifest added by this plan. Actions are pinned by 40-hex commit SHA with a `# vX.Y.Z` comment, repo-wide (Task 1 makes this a hard check). All bootstrap pins live in `infra/bootstrap/versions.env`, one `NAME_VERSION` plus one `NAME_SHA256_<amd64|arm64>` per artifact.
- Secrets in Git: only `SealedSecret` objects under `deploy/k8s/overlays/prod/sealed/`, plus `local-test-…` values in `deploy/k8s/overlays/e2e/secrets/*.env` and `infra/bootstrap/testdata/`. Plaintext prod inputs live in `deploy/k8s/overlays/prod/secrets.local/` (gitignored). The Sealed Secrets public cert is committed as `deploy/k8s/overlays/prod/sealed/sealing-cert.crt`. `.pem` and `.key` are gitignored, so it cannot be either of those.
- The secret inventory is defined once in `deploy/k8s/overlays/prod/secrets.tsv` (columns `name  namespace  required|optional  KEY,KEY,…`). It lists the 7 Plan 3 secrets (required) plus `alerting-secrets` (observability: `TELEGRAM_BOT_TOKEN,TELEGRAM_CHAT_ID`), `ops-secrets` (data: `S3_ENDPOINT,S3_BUCKET,S3_REGION,S3_ACCESS_KEY_ID,S3_SECRET_ACCESS_KEY,HEALTHCHECKS_URL,HEALTHCHECKS_BACKUP_URL`) and `cloudflared-secrets` (edge: `TUNNEL_TOKEN`), all three optional. A key with a trailing `?` (`GROQ_API_KEY?`, `GEMINI_API_KEY?`, `OIDC_GOOGLE_CLIENT_SECRET?`) may hold an empty value but must be present. Optional secrets are consumed only through `optional: true` refs.
- Prod overlay layout: `deploy/k8s/overlays/prod/{kustomization.yaml (aggregate: platform, tunnel, apps, sealed), domain/domain.yaml, secrets.tsv, platform/, tunnel/, apps/, sealed/}`. `sealed/` (wave -1) owns the namespaces, each annotated `argocd.argoproj.io/sync-options: Delete=false,Prune=false`. The image tags that CD bumps live only in `overlays/prod/apps/kustomization.yaml`.
- `APP_DOMAIN` is set in exactly one place: `overlays/prod/domain/domain.yaml` (a Component dir: kustomize forbids a slice loading `../domain.yaml`), a ConfigMap `app-domain` annotated `config.kubernetes.io/local-config: "true"`, key `APP_DOMAIN`, value `app.example.invalid` until go-live. Kustomize `replacements` copy it into the Ingress host, the web `WEB_BASE_URL`/`WEB_ORIGIN` host part (`delimiter: "/"`, `index: 2`), the identity-svc `MAIL_FROM` domain part (`delimiter: "@"`, `index: 1`) and the cloudflared `--http-host-header`.
- Argo CD: namespace `argocd`. Repo `https://github.com/nguyentuan2686/platform.git`. Root Application `root` (path `deploy/argocd/apps`). Child apps `prod-sealed` (wave -1), `prod-platform` (wave 0), `prod-tunnel` (wave 1) and `prod-apps` (wave 1). All have `syncPolicy.automated: {prune: true, selfHeal: true}`, `syncOptions: [ServerSideApply=true]` and `targetRevision: main`. Only the bootstrap may override the revision (`GITOPS_REVISION`).
- Network (spec §11): k3s runs with `--disable=servicelb`, and Traefik is a `ClusterIP` Service (HelmChartConfig). ufw defaults to deny incoming. Allowed in: `lo`, `tailscale0`, pod CIDR `10.42.0.0/16` and service CIDR `10.43.0.0/16`. `22/tcp` from anywhere stays allowed only while Tailscale is not up. Nothing listens publicly. Public traffic arrives only through the outbound cloudflared tunnel to `http://traefik.kube-system.svc.cluster.local:80`.
- CD commits touch only `deploy/k8s/overlays/` (CLAUDE.md "Never"). Their message ends with `[skip ci]` (spec §7 CD 1), and they are authored by the GitHub App installation token.
- Gating rule: a job that needs a missing secret or variable still runs a `gate` step/job. That gate prints `::notice::<feature> skipped: <NAME> not set (see docs/runbooks/go-live.md)` and exits 0, and the dependent steps are skipped by `if:`. A partial set of a feature's secrets is an error (`::error::`, exit 1), not a skip.
- GitHub names: secrets `CD_APP_PRIVATE_KEY`, `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` and `PROD_KUBECONFIG`; variable `CD_APP_ID`.
- Alerting (spec §10): rule uids `error-rate-high`, `latency-p95-high`, `pod-restart-loop`, `consumer-lag-rising`, `disk-usage-high`, `deploy-failed`. Grafana folder `Platform`, rule group `platform`, evaluation interval `1m`. Labels: `severity=critical` for error-rate, restart-loop, disk and deploy; `warning` for p95 and lag. `noDataState: OK`, `execErrState: Error`. Contact point `telegram`, used only when `TELEGRAM_BOT_TOKEN` is non-empty.
- Retention (spec §10): Prometheus `15d`, Loki `168h`, Tempo `168h`.
- Backup: CronJob `pg-backup` (ns `data`), schedule `0 3 * * *` with `timeZone: Asia/Ho_Chi_Minh`. Databases are `orders notify reading_tuvi identity payment` (env `BACKUP_DATABASES`). Object key `pg/<UTC %Y%m%dT%H%M%SZ>/<db>.dump` (`pg_dump -Fc --no-owner --no-acl`). Remote objects older than `14d` are deleted after a successful upload. Heartbeat: CronJob `heartbeat` (ns `data`), `*/5 * * * *`.
- Every wait is a deadline loop on observable state (CLAUDE.md Testing). Every script exits non-zero on the first failure and prints `OK <check>` / `FAIL <check>: <reason>`, as Plan 3's scripts do.

## Review Focus

1. **Out-of-order or partial CD runs.** Two merges in quick succession can finish CI in either order. A run must never move a service's tag to an older commit. A service not rebuilt in a commit keeps its tag. Test in Task 7 (`cd-bump.test.sh`: `older_sha_is_not_applied`, `unpublished_service_keeps_tag`).
2. **Bootstrap on a fresh VPS without Tailscale.** Enabling the firewall must not lock Tuấn out of SSH and must not leave 80/443/6443 reachable publicly. A second run must change nothing. Test in Task 4 (`infra/bootstrap/verify.sh`: `ssh-kept-without-tailscale`, `no-public-listeners`, `second-run-no-changes`).
3. **Optional secrets absent before go-live.** Without `alerting-secrets`, `ops-secrets` and `cloudflared-secrets`, Grafana, the CronJobs and the tunnel must still come up. Once backup is marked required, a missing S3 config must fail loudly. Test in Task 4 (CI seals only `required` secrets, and `prod-platform` must reach Healthy), Task 5 (`contact point absent when token empty`) and Task 6 (`backup_required_without_s3_fails`).
4. **Secret handling mistakes.** Three mistakes must each be refused with a clear message: sealing from a tracked file, sealing `local-test-…` values into prod, and a key or name that drifts from `secrets.tsv`. Test in Task 3 (`seal-secrets.test.sh`, validator `secrets-manifest` fixtures).
5. **Alert noise.** An idle service with one 5xx, or the slow LLM routes `/tuvi/qa/readings*`, must not page. A real 10 % error rate or a restart loop must. Test in Task 5 (promtool unit tests on the extracted rule expressions).

---

### Task 1: SHA-pin every action repo-wide, and the `ops-scripts` CI job

**Files:**
- Create: `scripts/check-action-pins.sh`, `scripts/test/check-action-pins.test.sh`, `scripts/testdata/action-pins/{good.yml,bad-tag.yml,bad-short-sha.yml}`
- Modify: every file in `.github/workflows/*.yml` (all `uses:` → `@<sha> # vX.Y.Z`), `.github/workflows/ci.yml` (filter `ops`, job `ops-scripts`, `ci-gate.needs`), `.github/dependabot.yml` (no change in ecosystems; add a comment that Dependabot keeps SHA pins current)

**Interfaces:**
- Produces:
  - `scripts/check-action-pins.sh [file...]` (default: `.github/workflows/*.yml` plus any `action.yml` under `.github/`). For each `uses:` value, it allows `./…` (local) and `<owner>/<repo>[/<path>]@<40 lowercase hex>`. Each other value prints `PIN: <file>:<line>: <value>` and the script exits 1.
  - CI job `ops-scripts` (ubuntu-24.04). It runs pinned `shellcheck` over `scripts/**/*.sh` and `infra/**/*.sh`, then pinned `actionlint`, then `scripts/check-action-pins.sh`, then every `scripts/test/*.test.sh` (each prints `ok <name>` lines and exits non-zero on the first failure). It runs when `changes.outputs.ops == 'true' || root == 'true'`. Filter `ops`: `scripts/**`, `infra/**`, `deploy/argocd/**`, `.github/**`. The job is added to `ci-gate.needs`. Later tasks add their `*.test.sh` files here, and nothing else needs to change.
  - Convention for `scripts/test/*.test.sh`: bash, `set -euo pipefail`, one function per case named as in this plan, temp dirs under `mktemp -d` removed by trap. A test runs in under 30 s with no network and no cluster.

- [ ] **Step 1: Write `check-action-pins.test.sh`.** Cases: `good_file_passes` (fixture with `./.github/workflows/_x.yml` and a SHA-pinned action) exits 0; `tag_is_rejected` (`actions/checkout@v7`) exits 1 with `PIN: …bad-tag.yml:`; `short_sha_is_rejected` (`@ed142fd`) exits 1.
- [ ] **Step 2: Run** `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/test/check-action-pins.test.sh` → FAIL (script missing).
- [ ] **Step 3: Implement** the script. Then pin every action in all workflows: `actions/checkout`, `actions/setup-java`, `actions/setup-node`, `actions/upload-artifact`, `actions/download-artifact`, `docker/setup-buildx-action`, `docker/build-push-action`, `docker/login-action` and `github/codeql-action/{init,analyze}`. Use the commit SHA of the exact release tag in use today, resolved with `gh api repos/<o>/<r>/commits/<tag> --jq .sha`, keeping the tag as the comment. Add the `ops-scripts` job.
- [ ] **Step 4: Run** the test → PASS. Run `bash scripts/check-action-pins.sh` → exit 0 on the real workflows. Run `docker run --rm -v "$PWD:/w" -w /w rhysd/actionlint:<pinned>` → no errors.
- [ ] **Step 5: Commit** `ci: pin every action by commit SHA and add ops-scripts job`

---

### Task 2: Plan 3 follow-ups: Postgres startup gate, Kafka topic readiness, Rust log filter, agent double-count check

**Files:**
- Create: `libs/java/platform-starter/src/main/java/dev/platform/starter/messaging/KafkaTopicsHealthIndicator.java`, `libs/java/platform-starter/src/test/java/dev/platform/starter/messaging/KafkaTopicsHealthIndicatorIT.java`, `deploy/k8s/base/components/wait-for-postgres/{kustomization.yaml,patch.yaml}` (Kustomize Component), `scripts/testdata/k8s-validate/bad-no-db-gate/{kustomization.yaml,expect}`
- Modify: `.../messaging/MessagingMetricsAutoConfiguration.java` (register the indicator bean), `libs/java/platform-starter/src/main/resources/platform-defaults.properties` (`ready.include=readinessState,db,kafkaTopics`), `deploy/k8s/base/{order-svc,notify-svc,reading-tuvi,identity-svc,payment-svc}/kustomization.yaml` (`components: [../components/wait-for-postgres]`), `deploy/k8s/base/{notify-svc,chart-svc}/deployment.yaml` (remove `RUST_LOG`), `deploy/local/docker-compose.yml` (remove the two `RUST_LOG:` lines; `.env.example` keeps a comment that setting `RUST_LOG` replaces the quiet default filter), `scripts/k8s-validate.sh` (check `db-gate`), `scripts/e2e-observability.sh` (check `no-agent-double-count`)

**Interfaces:**
- Produces:
  - `KafkaTopicsHealthIndicator(KafkaAdmin admin, ObjectProvider<NewTopic> topics, Duration timeout)` implements `HealthIndicator`, bean name `kafkaTopics`. The bean is created `@ConditionalOnBean(KafkaAdmin.class)`, with the timeout from property `platform.kafka.topics-health-timeout` (default `3s`). `health()` describes all declared topic names via `AdminClient` with that timeout. If all exist → `UP`, detail `topics`. If some are missing, it calls `admin.createOrModifyTopics(missing…)` once per call and returns `DOWN` with detail `missing` (the next probe re-checks). On broker error or timeout → `DOWN` with detail `error` (message only, no stack trace), logged at WARN. It never throws and never blocks longer than `timeout`. With no declared topics → `UP`.
  - Component `wait-for-postgres`: an initContainer `wait-for-postgres` (image `postgres:17-alpine@sha256:<digest>`, same as the platform StatefulSet) running `until pg_isready -h postgres.data.svc.cluster.local -p 5432 -t 2; do sleep 2; done`. It has the same securityContext as the app container (non-root uid 70, read-only rootfs, drop ALL) and resources `requests {cpu: 10m, memory: 16Mi}, limits {memory: 16Mi}`. The loop has no deadline on purpose: the pod's own `activeDeadline` is not set, and the init container shows as `Init:0/1` rather than crash-looping.
  - Validator check `db-gate`: every Deployment in ns `apps` whose containers reference (via `envFrom` or `secretKeyRef`) a Secret of the same build that has a key starting with `DB_` must have an initContainer named `wait-for-postgres`. The check runs on the e2e build, where the Secrets exist.
  - e2e check `no-agent-double-count`: Prometheus `count(messaging_process_duration_seconds_count{outcome=""})` is empty or `0`. Only platform metrics carry `outcome`, so any agent-emitted series would show up here.

- [ ] **Step 1: Write failing tests**
  - `KafkaTopicsHealthIndicatorIT` (Testcontainers Redpanda, `KafkaAdmin` pointed at it, one `NewTopic` `t.health.v1`):
    - `missing_topic_is_created_and_reported_down_then_up`: first `health()` → `DOWN` with `missing=[t.health.v1]`; Awaitility (deadline 30 s) until `health()` is `UP`; topic exists per `AdminClient`.
    - `broker_unreachable_is_down_within_timeout`: `KafkaAdmin` on `localhost:1`, timeout 1 s. `health()` returns `DOWN` in < 3 s (`assertTimeoutPreemptively`), with no exception.
    - `no_declared_topics_is_up`.
  - Validator fixture `bad-no-db-gate` (Deployment with `envFrom: order-svc-secrets`, no init) → `POLICY: db-gate`. Add it to `--self-test`.
- [ ] **Step 2: Run** `mvn -q -pl libs/java/platform-starter verify` → FAIL (class missing). Run `bash scripts/k8s-validate.sh --self-test` (in `alpine/k8s`) → FAIL on `bad-no-db-gate`.
- [ ] **Step 3: Implement** the indicator, its registration and the property, the component and its use in the 5 DB bases, the `db-gate` check, the `RUST_LOG` removals, and the e2e check (add the `check no-agent-double-count` line after `red-consumer`).
- [ ] **Step 4: Run** `mvn -q verify` (whole reactor) → PASS. The validator self-test and full run → PASS. `docker compose -f deploy/local/docker-compose.yml config -q` → exit 0. `bash scripts/e2e-observability.sh http://127.0.0.1:1 http://127.0.0.1:1 http://127.0.0.1:1` with `E2E_OBS_DEADLINE_S=5` → still exits non-zero (the checks can fail).
- [ ] **Step 5: Commit** `fix(platform): gate DB pods on Postgres, Kafka topics in readiness, quiet Rust filter`. The PR runs `k8s-e2e`. Read the log for `OK no-agent-double-count`, and confirm in `kubectl get pods` (diagnostics, or the step log of `rollout status`) that app pods have `RESTARTS 0`.

---

### Task 3: Prod overlay split, APP_DOMAIN, cloudflared, Argo CD app-of-apps, Sealed Secrets tooling, `public-url`

**Decision (refines author call 1):** cloudflared is GitOps-managed in `overlays/prod/tunnel/` rather than installed by bootstrap. Spec §6 places it under `deploy/k8s`, and switching from the quick tunnel to a named tunnel then becomes a Git change that Argo applies, with no need to re-run bootstrap. The prod overlay is split into slices because `overlays/prod` includes `../../platform`, and two Argo apps cannot own the same objects.

**Files:**
- Create: `deploy/k8s/overlays/prod/{domain.yaml,secrets.tsv}`, `deploy/k8s/overlays/prod/platform/kustomization.yaml`, `deploy/k8s/overlays/prod/apps/kustomization.yaml` (moved from today's prod `kustomization.yaml` + `ingress.yaml` + `patches/`), `deploy/k8s/overlays/prod/tunnel/{kustomization.yaml,namespace.yaml}`, `deploy/k8s/overlays/prod/sealed/kustomization.yaml` (`resources: []`), `deploy/k8s/components/cloudflared-quick/{kustomization.yaml,deployment.yaml}`, `deploy/k8s/components/cloudflared-named/{kustomization.yaml,deployment.yaml}`
- Create: `deploy/argocd/root.yaml`, `deploy/argocd/apps/{kustomization.yaml,prod-sealed.yaml,prod-platform.yaml,prod-tunnel.yaml,prod-apps.yaml}`
- Create: `scripts/seal-secrets.sh`, `scripts/public-url`, `scripts/test/{seal-secrets.test.sh,public-url.test.sh}`, `scripts/testdata/public-url/{quick.log,named.log,none.log}`, `scripts/testdata/k8s-validate/{prod-sealed-unknown,prod-domain-mismatch,secrets-manifest-drift,argocd-bad-path}/`
- Modify: `deploy/k8s/overlays/prod/kustomization.yaml` (aggregate of the four slices), `scripts/k8s-validate.sh`, `.gitignore` (`deploy/k8s/overlays/prod/secrets.local/`)

**Interfaces:**
- Consumes: Plan 3 secret names; the validator.
- Produces:
  - `overlays/prod/apps`: the 7 bases, `ingress.yaml`, patches and `images:` (`newTag: main` until CD). It also pulls in the `domain.yaml` replacements. `overlays/prod/platform`: `../../../platform`. `overlays/prod/tunnel`: Namespace `edge` + component `cloudflared-quick` + domain replacement.
  - cloudflared Deployment `cloudflared` (ns `edge`, 1 replica, `cloudflare/cloudflared:<ver>@sha256:…`, uid 65532, read-only rootfs, 64Mi, cpu 10m, readiness/liveness `GET :2000/ready`).
    - Quick args: `tunnel --no-autoupdate --metrics 0.0.0.0:2000 --url http://traefik.kube-system.svc.cluster.local:80 --http-host-header <APP_DOMAIN>`.
    - Named args: `tunnel --no-autoupdate --metrics 0.0.0.0:2000 run`, with env `TUNNEL_TOKEN` from `cloudflared-secrets` (required in this component). The public hostname → `http://traefik.kube-system.svc.cluster.local:80` mapping is configured in the Cloudflare dashboard (token-managed tunnel).
  - `scripts/public-url [--namespace edge] [--log-file F]`: from `--log-file` if given, else from `kubectl -n edge logs deploy/cloudflared --tail=2000`. Prints the last `https://[a-z0-9-]+\.trycloudflare\.com` match. If the Deployment runs `run` (named) or no match exists, it prints `https://$APP_DOMAIN` read from `overlays/prod/domain/domain.yaml` when that domain is not `*.invalid`, else exits 2 with `no public URL yet`.
  - `deploy/argocd/root.yaml`: Application `root` (ns `argocd`, project `default`, source path `deploy/argocd/apps`, destination in-cluster ns `argocd`, automated prune/selfHeal). The child apps are Applications with the paths `deploy/k8s/overlays/prod/{sealed,platform,tunnel,apps}`, the waves from Global Constraints, `destination.server: https://kubernetes.default.svc`, and finalizer `resources-finalizer.argocd.argoproj.io`.
  - `scripts/seal-secrets.sh --cert <crt> [--in deploy/k8s/overlays/prod/secrets.local] [--out deploy/k8s/overlays/prod/sealed] [--only name,…] [--allow-test-values]`. For each row of `secrets.tsv` that has `<in>/<name>.env`, it runs `kubeseal --cert <crt> --scope strict --format yaml` on a Secret built from that env file in the row's namespace. It writes `<out>/<name>.sealedsecret.yaml` and rewrites `<out>/kustomization.yaml` `resources:` sorted. It refuses with exit 1 and `SEAL: <reason>` in these cases:
    - a `required` row has no env file (unless `--only`);
    - the env file's key set is not exactly the row's keys;
    - any value is empty or starts with `local-test-` (unless `--allow-test-values`);
    - an input file is tracked by Git (`git ls-files --error-unmatch`);
    - `<in>` is inside the repo and not gitignored.
    `kubeseal` is pinned in `infra/bootstrap/versions.env`, and the script installs it to `~/.cache/platform/bin` with a sha256 check if it is missing.
  - Validator additions:
    - `prod-sealed` (prod build): every SealedSecret `name/namespace` is a row in `secrets.tsv`, and its `spec.encryptedData` keys equal the row's keys.
    - `--require-sealed` flag: every `required` row has a SealedSecret.
    - `prod-domain`: the Ingress hosts, the host of `WEB_ORIGIN`/`WEB_BASE_URL` and the cloudflared `--http-host-header` value all equal `APP_DOMAIN`.
    - `secrets-manifest`: every non-optional `secretRef`/`secretKeyRef` in the prod build names a `secrets.tsv` row with a matching namespace and contains the key; every `overlays/e2e/secrets/<name>.env` key set equals its row's keys.
    - `argocd`: every Application in `deploy/argocd/**` has a `spec.source.path` that exists and builds with `kubectl kustomize`, uses `targetRevision: main` and has an automated prune+selfHeal policy.

- [ ] **Step 1: Write failing tests**
  - `seal-secrets.test.sh` (generates a throwaway cert with `openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=local-test`; `kubeseal --cert` encrypts offline): `seals_all_required_rows`; `key_set_mismatch_refused`; `test_value_refused_without_flag`; `tracked_input_refused` (temp git repo); `kustomization_resources_sorted`; `rerun_rewrites_same_files` (the set of files is the same; ciphertext differs by design).
  - `public-url.test.sh`: `quick_log_prints_trycloudflare_url` (fixture with two URLs → the last); `none_log_without_domain_exits_2`; `named_log_with_domain_prints_domain` (temp copy of `domain.yaml` with `app.example.com`, via env `PUBLIC_URL_DOMAIN_FILE`).
  - Validator fixtures `prod-sealed-unknown`, `prod-domain-mismatch`, `secrets-manifest-drift` and `argocd-bad-path`, each with an `expect` file, in `--self-test`.
- [ ] **Step 2: Run** the tests and the self-test in `alpine/k8s:1.33.4` (add `apk add --no-cache openssl git` plus the pinned kubeseal) → FAIL.
- [ ] **Step 3: Implement** the split (move the files with `git mv`), `domain.yaml` with its replacements, the components, the Argo manifests, the two scripts, the validator checks and the `.gitignore` line. `kubectl kustomize deploy/k8s/overlays/prod` must render the same app objects as before the split, apart from the new tunnel/edge objects. Check this with a `diff` of sorted `kind/ns/name` lists before and after.
- [ ] **Step 4: Run** the tests → PASS. The validator self-test and full run → PASS. `bash scripts/k8s-validate.sh --require-sealed deploy/k8s/overlays/prod` → FAIL listing the 7 required secrets (expected until go-live; this is the command the runbook uses).
- [ ] **Step 5: Commit** `feat(gitops): prod overlay slices, APP_DOMAIN, cloudflared, Argo CD app-of-apps, sealing tooling`

---

### Task 4: `infra/bootstrap` and the `bootstrap` CI job (x86 + ARM, run twice, Argo syncs the PR commit)

**Decision (refines author call 1):** bootstrap also hardens the host, because spec §11 says no public ports. It disables servicelb, makes Traefik ClusterIP and sets up ufw with an SSH lockout guard. It can restore an existing sealing key (§12 host migration). CI runs a matrix on `ubuntu-24.04` and `ubuntu-24.04-arm` because §16.3 requires both architectures.

**Files:**
- Create: `infra/bootstrap/{bootstrap.sh,versions.env,verify.sh,traefik-config.yaml,argocd-cm-patch.yaml,README.md}`, `infra/bootstrap/lib/{k3s.sh,argocd.sh,sealed.sh,tailscale.sh,firewall.sh,common.sh}`, `infra/bootstrap/testdata/sealing-key/{tls.crt,tls.key.local-test}` (throwaway key generated for CI only; the file name marks it `local-test`), `.github/workflows/_bootstrap.yml`, `scripts/verify-deploy.sh` (Argo wait part; Task 8 adds smoke + rollback)
- Modify: `.github/workflows/ci.yml` (filter `infra`: `infra/**`, `deploy/argocd/**`, `deploy/k8s/overlays/prod/**`, `.github/workflows/_bootstrap.yml`; job `bootstrap`; `ci-gate.needs`)

**Interfaces:**
- Consumes: `deploy/argocd/root.yaml` and `scripts/seal-secrets.sh` (Task 3).
- Produces:
  - `sudo infra/bootstrap/bootstrap.sh`. Environment inputs: `GITOPS_REPO_URL` (default the repo), `GITOPS_REVISION` (default `main`), `GITOPS_CI_MODE` (`0`/`1`), `TS_AUTHKEY` (optional), `TS_HOSTNAME` (default `platform-prod`), `SEALED_SECRETS_KEY_DIR` (optional dir with `tls.crt`+`tls.key`), `BOOTSTRAP_FIREWALL` (default `1`). It checks `/etc/os-release` (Ubuntu 24.04) and arch (`x86_64→amd64`, `aarch64→arm64`), else exit 1. Steps, each printing exactly one of `CHANGED <component>: <what>`, `OK <component>` or `SKIP <component>: <reason>`:
    1. `k3s`: downloads the pinned `k3s`/`k3s-arm64` binary and `install.sh` from the release tag, both sha256-verified, then `INSTALL_K3S_SKIP_DOWNLOAD=true INSTALL_K3S_VERSION=<pinned>`. Config `/etc/rancher/k3s/config.yaml`: `disable: [servicelb]`, `write-kubeconfig-mode: "0600"`, `tls-san: [<TS_HOSTNAME>]`. Puts `traefik-config.yaml` (HelmChartConfig: `service.type: ClusterIP`) into `/var/lib/rancher/k3s/server/manifests/`. It is unchanged when the binary version and the config file hash match.
    2. `firewall`: ufw rules per Global Constraints. If `tailscale0` is not up, it keeps `22/tcp` and prints `WARN firewall: public SSH kept until Tailscale is up`.
    3. `tailscale`: `SKIP` when `TS_AUTHKEY` is unset. Otherwise it installs the pinned static tarball, runs `tailscaled` as a systemd unit and `tailscale up --authkey … --hostname $TS_HOSTNAME --ssh=false`, then re-runs the firewall step (drops public 22).
    4. `sealed-secrets`: if `SEALED_SECRETS_KEY_DIR` is set, it first applies the Secret `sealed-secrets-key-restored` (ns `kube-system`, label `sealedsecrets.bitnami.com/sealed-secrets-key=active`, type `kubernetes.io/tls`). Then the pinned `controller.yaml` (sha256-verified).
    5. `argocd`: the pinned `install.yaml` (sha256-verified) applied server-side into ns `argocd`, plus `argocd-cm-patch.yaml` (the Application health customization, so sync waves across child apps wait on health).
    6. `gitops-root`: `root.yaml` with `targetRevision: $GITOPS_REVISION`. When `GITOPS_REVISION != main` or `GITOPS_CI_MODE=1`, `spec.source.kustomize.patches` sets every child Application's `targetRevision` to `$GITOPS_REVISION`. With `GITOPS_CI_MODE=1`, it also removes `syncPolicy.automated` from `prod-tunnel` and `prod-apps`, so CI pulls no prod images and opens no public tunnel.
    Every kubectl step runs `kubectl diff --server-side` first and applies only on exit 1; a diff error exits the script non-zero. The last line is `bootstrap: <n> changed`. Total run is bounded by per-step `kubectl wait/rollout status --timeout`.
  - `infra/bootstrap/verify.sh [--expect-unchanged <log>]` prints `OK|FAIL <check>`. Checks:
    - `k3s-ready`: node Ready.
    - `traefik-clusterip`.
    - `sealed-controller-ready`.
    - `argocd-ready`: all Deployments/StatefulSets in `argocd` Available.
    - `no-public-listeners`: `ss -ltnH` shows no listener on `0.0.0.0`/`[::]` ports 80, 443 or 8080. For 6443, ufw has no `ALLOW` rule other than the tailscale0/pod/service ranges.
    - `ssh-kept-without-tailscale`: when `tailscale0` is absent, ufw allows `22/tcp`.
    - `second-run-no-changes`: the log passed with `--expect-unchanged` contains no `CHANGED ` line and ends `bootstrap: 0 changed`.
  - `scripts/verify-deploy.sh --app <name>[,<name>…] --revision <sha> [--deadline-s 900] [--no-smoke]`. Deadline loop until each Application has `status.sync.revision == <sha>`, `status.sync.status == Synced` and `status.health.status == Healthy`. With `--expect-outofsync <name>`, that app must have `sync.status == OutOfSync` and no `ComparisonError` condition. On timeout it prints `FAIL argo-<app>: sync=<…> health=<…> revision=<…> conditions=<…>`.
  - `_bootstrap.yml` (`workflow_call`), job `bootstrap` with matrix `runner: [ubuntu-24.04, ubuntu-24.04-arm]`, `timeout-minutes: 45`, `permissions: contents: read`. Steps:
    1. checkout;
    2. `sudo -E GITOPS_REVISION=${{ github.event.pull_request.head.sha || github.sha }} GITOPS_CI_MODE=1 SEALED_SECRETS_KEY_DIR=infra/bootstrap/testdata/sealing-key infra/bootstrap/bootstrap.sh | tee run1.log`;
    3. the same again `| tee run2.log`;
    4. `verify.sh --expect-unchanged run2.log`;
    5. `sealed-key-restored`: `kubeseal --fetch-cert` equals `testdata/sealing-key/tls.crt`;
    6. seal with `scripts/seal-secrets.sh --cert <fetched> --in deploy/k8s/overlays/e2e/secrets --out $RUNNER_TEMP/sealed --allow-test-values`, which seals only the `required` rows, so `alerting-secrets`, `ops-secrets` and `cloudflared-secrets` stay absent. Then `kubectl apply -k $RUNNER_TEMP/sealed`. Deadline loop until each Secret exists with the expected keys, and `postgres-secrets.POSTGRES_PASSWORD` decodes to the e2e value (`secret-materialised`);
    7. `scripts/verify-deploy.sh --app root,prod-sealed,prod-platform --revision <sha> --no-smoke --expect-outofsync prod-apps,prod-tunnel`;
    8. negative check: `scripts/verify-deploy.sh --app prod-platform --revision 0000000000000000000000000000000000000000 --deadline-s 20 --no-smoke` must exit non-zero;
    9. on failure, diagnostics (`kubectl get applications -n argocd -o yaml`, events, pod logs in `argocd`, `data`, `observability`, `kube-system`) uploaded as `bootstrap-diagnostics-<arch>`.
  - `ci.yml` job `bootstrap`: `if: needs.changes.outputs.infra == 'true' || needs.changes.outputs.root == 'true' || (github.event_name == 'push' && github.ref == 'refs/heads/main')`. The job is added to `ci-gate.needs`.

- [ ] **Step 1: Write `infra/bootstrap/verify.sh` first.** Run `bash infra/bootstrap/verify.sh` locally in `alpine/k8s` with no cluster → prints `FAIL k3s-ready` and exits non-zero. Run `--expect-unchanged` on a fixture log containing `CHANGED k3s: …` → `FAIL second-run-no-changes`.
- [ ] **Step 2: Write `versions.env`** (resolve each sha256 from the vendor's published checksum file, never by hashing a download yourself), the `lib/*.sh` steps, `bootstrap.sh`, the Argo wait part of `verify-deploy.sh`, `_bootstrap.yml` and the `ci.yml` job. Run shellcheck and actionlint (Task 1 commands) → clean. `bash -n` on every script → OK.
- [ ] **Step 3: Push and open the PR.** Both matrix legs must show `OK` for every `verify.sh` check, `OK secret-materialised`, `OK sealed-key-restored`, `OK argo-root`, `OK argo-prod-sealed`, `OK argo-prod-platform` and `OK argo-prod-apps (OutOfSync, renders)`, and the negative check must exit non-zero. `prod-platform` Healthy without the optional secrets is the Review Focus 3 evidence for Grafana and the CronJobs once Tasks 5–6 land, so re-check this job on those PRs. For failures other than registry flakes, read the diagnostics and record a lesson with the `retro` skill.
- [ ] **Step 4: Commit** `feat(infra): idempotent host bootstrap (k3s, firewall, Tailscale, Sealed Secrets, Argo CD) verified in CI`

---

### Task 5: Alerts as code, Telegram contact point, cluster metrics for alerts, retention

Alerts need data the stack does not collect today. That covers container restarts, node disk usage and Argo app health. The collector therefore gains `k8s_cluster` and `hostmetrics` receivers and an Argo CD scrape job. The Argo scrape has no target in kind, so `deploy-failed` stays in NoData → OK there.

**Files:**
- Create: `deploy/k8s/platform/grafana/alerting/{rules.yaml,contact-point.tmpl.yaml,policy.tmpl.yaml,render.sh}`, `deploy/k8s/platform/observability/retention/` (override files for the otel-lgtm image; see Step 3), `scripts/alerts-promtool.sh`, `scripts/testdata/alerts/*.test.yaml`, `scripts/testdata/k8s-validate/bad-alert-metric/`, `docs/runbooks/alerts.md`
- Modify: `deploy/k8s/platform/grafana/kustomization.yaml` (ConfigMap `grafana-alerting`), `deploy/k8s/platform/grafana/metric-names.txt`, `deploy/k8s/platform/observability/{lgtm.yaml,otel-collector.yaml,otel-collector-config.yaml,rbac.yaml,kustomization.yaml}`, `deploy/k8s/overlays/e2e/{kustomization.yaml,secrets/alerting-secrets.env}`, `scripts/k8s-validate.sh` (check `alerts`), `scripts/e2e-observability.sh` (checks `alert-rules-loaded`, `alert-rules-healthy`, `contact-point`, `retention`), `deploy/local/docker-compose.yml` (lgtm mounts the rules; no contact point locally)

**Interfaces:**
- Consumes: metric names from Plan 3 Global Constraints; the validator's metric extraction (`check_dashboards`).
- Produces:
  - `rules.yaml`: Grafana alerting provisioning (`apiVersion: 1`, group `platform`, folder `Platform`, interval `1m`). Each rule has `data[0]` refId `A` (datasource uid `prometheus`, instant query holding the full PromQL including the comparison) and `data[1]` refId `B`, a threshold `A > 0` (with a `reduce last` step if Grafana requires it). Expressions, with `R = http_route!~"/health/.*|/tuvi/qa/readings.*"`:
    - `error-rate-high` (for `1m`): `(sum by (job) (rate(http_server_request_duration_seconds_count{R, http_response_status_code=~"5.."}[5m])) / sum by (job) (rate(http_server_request_duration_seconds_count{R}[5m])) > 0.05) and sum by (job) (rate(http_server_request_duration_seconds_count{R}[5m])) > 0.01`
    - `latency-p95-high` (for `5m`): `histogram_quantile(0.95, sum by (job, le) (rate(http_server_request_duration_seconds_bucket{R}[5m]))) > 1`
    - `pod-restart-loop` (for `0m`): `max by (k8s_namespace_name, k8s_pod_name) (delta(k8s_container_restarts[15m])) >= 3`
    - `consumer-lag-rising` (for `10m`): `max by (redpanda_group) (redpanda_kafka_consumer_group_lag_max) > 100 and max by (redpanda_group) (deriv(redpanda_kafka_consumer_group_lag_max[10m])) > 0`
    - `disk-usage-high` (for `10m`): `max by (mountpoint) (system_filesystem_utilization{mountpoint="/"}) > 0.8`
    - `deploy-failed` (for `10m`): `max by (name) (argocd_app_info{health_status=~"Degraded|Missing"}) > 0`
    Each rule has annotations `summary` and `runbook_url: https://github.com/nguyentuan2686/platform/blob/main/docs/runbooks/alerts.md#<uid>`. The exact Prometheus label names (`mountpoint`, `k8s_pod_name`, …) are confirmed against the e2e Prometheus. When they differ, rules, metric-names, tests and this list change together.
  - `render.sh` runs in initContainer `alerting-render` (image: the lgtm image itself, so no extra pull), command `sh /alerting/render.sh`. It copies `rules.yaml` into emptyDir `/provisioning-alerting` mounted at the image's Grafana `conf/provisioning/alerting/`. If `TELEGRAM_BOT_TOKEN` is non-empty, it also renders `contact-point.yaml` (type `telegram`, name `telegram`, `bottoken`/`chatid` from env, `disableResolveMessage: false`) and `policy.yaml` (receiver `telegram`, `group_by: [alertname, job]`, `group_wait 30s`, `group_interval 5m`, `repeat_interval 4h`). Otherwise it renders neither, so Grafana keeps its built-in default policy, and it logs `alerting: TELEGRAM_BOT_TOKEN unset, no contact point`. The env comes from `envFrom: [{secretRef: {name: alerting-secrets, optional: true}}]` on the init container only. The token never reaches the Grafana container env, only the rendered file.
  - Collector: receiver `k8s_cluster` (`collection_interval: 30s`, `node_conditions_to_report: [Ready, DiskPressure]`). Receiver `hostmetrics` (`root_path: /hostfs`, scraper `filesystem` with `metrics.system.filesystem.utilization.enabled: true`, `exclude_fs_types` for `tmpfs, overlay, squashfs, nsfs, proc, sysfs`), with hostPath `/` → `/hostfs` read-only. Prometheus scrape job `argocd` → `argocd-metrics.argocd.svc.cluster.local:8082`, every 30 s. RBAC: ClusterRole + Binding `otel-collector` with get/list/watch on the resources `k8s_cluster` documents, nothing else. The ADR (Task 8) notes that on multi-node clusters `k8s_cluster` must move to a single-replica Deployment.
  - Retention: Prometheus `--storage.tsdb.retention.time=15d`, Loki `limits_config.retention_period: 168h` with `compactor.retention_enabled: true`, Tempo `compactor.compaction.block_retention: 168h`, applied by mounting override files or env into the otel-lgtm image (see Step 3).
  - `scripts/alerts-promtool.sh [rules.yaml] [tests dir]` converts each Grafana rule into a Prometheus rule (`alert: <uid>`, `expr: <A expr>`, `for: <for>`, labels) in a temp file. It then runs `promtool test rules` (image `prom/prometheus:<pinned>@sha256:…`, entrypoint `promtool`) on `scripts/testdata/alerts/*.test.yaml`, whose `rule_files` point at the temp file.
  - `docs/runbooks/alerts.md`: one `## <uid>` section per rule (meaning, first queries, likely fixes); each rule's `runbook_url` anchors there.
  - Validator check `alerts`: `rules.yaml` parses, the uids equal the six in Global Constraints, every `data[].datasourceUid` is `prometheus` or `__expr__`, and every metric identifier in an `A` expr is in `metric-names.txt` (reusing the dashboard extraction).
  - e2e checks (Grafana on `:3000`, basic auth `admin:admin` as the otel-lgtm image default):
    - `alert-rules-loaded`: `GET /api/v1/provisioning/alert-rules` uids ⊇ the six.
    - `alert-rules-healthy`: deadline loop on `GET /api/prometheus/grafana/api/v1/rules` until every rule in group `platform` has `health` in `ok|nodata` (never `error`).
    - `contact-point`: `GET /api/v1/provisioning/contact-points` has name `telegram` (e2e supplies `alerting-secrets.env` with `TELEGRAM_BOT_TOKEN=local-test-telegram-token`, `TELEGRAM_CHAT_ID=local-test-chat`). Sending to Telegram is not tested.
    - `retention`: Prometheus `GET /api/v1/status/flags` has `storage.tsdb.retention.time == "15d"`; Loki `GET :3100/config` contains `retention_period: 1w` or `168h`; Tempo `GET :3200/status/config` contains `block_retention: 168h0m0s` (or `1w`).

- [ ] **Step 1: Write failing tests**
  - `scripts/testdata/alerts/` promtool tests (Review Focus 5):
    - `error_rate_fires_at_10_percent` (1 rps, 10 % 5xx for 10 m → firing for `job=order-svc`);
    - `error_rate_silent_on_idle_service` (1 request, a 500, in 5 m → no alert);
    - `latency_ignores_qa_routes` (p95 5 s only on `/tuvi/qa/readings` → no alert; same on `/orders` → alert);
    - `restart_loop_fires_at_3_in_15m`;
    - `lag_rising_fires_only_when_growing` (flat 500 → none; growing from 150 → alert);
    - `disk_fires_above_80_percent`;
    - `deploy_failed_after_10m_degraded`.
  - Validator fixture `bad-alert-metric` (rule uses `http_requests_total`) → `POLICY: alerts`.
  - In `e2e-observability.sh`, add the four check names. Run with the dead URLs → they FAIL.
- [ ] **Step 2: Run** `bash scripts/alerts-promtool.sh` → FAIL (`rules.yaml` missing). Validator `--self-test` → FAIL on the new fixture.
- [ ] **Step 3: Implement.** First inspect the image: `docker run --rm --entrypoint sh grafana/otel-lgtm:0.41.1 -c 'ls /otel-lgtm /otel-lgtm/grafana/conf/provisioning; cat /otel-lgtm/run-*.sh; ls /otel-lgtm/*.yaml'` (a light local command, no stack). Then choose, in this order of preference: (a) an env var the run scripts already honour, (b) mounting an override config file by `subPath` at the path the run script reads. Record the choice in a comment in `lgtm.yaml`. Then write the rules, templates, `render.sh`, the init container, the collector changes, RBAC, the e2e secret and the compose mount.
- [ ] **Step 4: Run** `bash scripts/alerts-promtool.sh` → all promtool tests PASS. Validator → PASS. `docker compose … config -q` → 0. Push. In `k8s-e2e`, expect `OK alert-rules-loaded`, `OK alert-rules-healthy`, `OK contact-point` and `OK retention`. In `bootstrap`, `prod-platform` must still be Healthy with no `alerting-secrets`.
- [ ] **Step 5: Commit** `feat(observability): alert rules as code, Telegram contact point, cluster metrics, retention`

---

### Task 6: Postgres backup to S3-compatible storage, restore script, external heartbeat

**Files:**
- Create: `deploy/k8s/platform/ops/{kustomization.yaml,pg-backup-cronjob.yaml,heartbeat-cronjob.yaml,backup.sh,heartbeat.sh}`, `scripts/pg-restore.sh`, `scripts/templates/pg-restore-job.yaml`, `scripts/e2e-backup.sh`, `scripts/test/backup-script.test.sh`, `deploy/k8s/overlays/e2e/{minio.yaml,secrets/ops-secrets.env}`
- Modify: `deploy/k8s/platform/kustomization.yaml` (add `ops`), `deploy/k8s/overlays/e2e/kustomization.yaml` (MinIO, `ops-secrets` generator, `BACKUP_REQUIRED=1` patch), `deploy/k8s/overlays/prod/platform/kustomization.yaml` (patch `BACKUP_REQUIRED=0` with a `# go-live: set 1` comment), `scripts/k8s-e2e.sh` (run `scripts/e2e-backup.sh` last)

**Interfaces:**
- Consumes: `postgres-secrets` (`POSTGRES_PASSWORD`), `ops-secrets` (optional).
- Produces:
  - `backup.sh <dump|upload>` (ConfigMap `ops-scripts`, `defaultMode: 0555`). Env: `BACKUP_DATABASES`, `BACKUP_REQUIRED` (`0|1`), `BACKUP_KEEP` (`14d`), `S3_*`, `HEALTHCHECKS_BACKUP_URL`, `PGHOST=postgres.data.svc.cluster.local`, `PGUSER=postgres`, `PGPASSWORD` from `postgres-secrets`, `STAMP` (set by the dump step into `/backup/STAMP`). Behaviour:
    - When `S3_ENDPOINT` or `S3_BUCKET` is empty: `BACKUP_REQUIRED=1` → `backup: S3 not configured` and exit 1; else → `backup: S3 not configured, skipping (BACKUP_REQUIRED=0)` and exit 0.
    - `dump`: `pg_dump -Fc --no-owner --no-acl -d <db> -f /backup/<db>.dump` for each db; a failure exits 1.
    - `upload`: `rclone copy /backup ":s3,provider=Other,endpoint=$S3_ENDPOINT,region=$S3_REGION,env_auth=false:$S3_BUCKET/pg/$STAMP"` (keys via `RCLONE_S3_ACCESS_KEY_ID`/`RCLONE_S3_SECRET_ACCESS_KEY`), then `rclone delete --min-age $BACKUP_KEEP` on `pg/`. It then `curl`-pings `HEALTHCHECKS_BACKUP_URL` when set. Prints `backup: uploaded <n> dumps to pg/<STAMP>`.
  - CronJob `pg-backup`: schedule/timeZone per Global Constraints, `concurrencyPolicy: Forbid`, `startingDeadlineSeconds: 3600`, `backoffLimit: 1`, history 1/3. initContainer `dump` (`postgres:17-alpine@…`, uid 70), container `upload` (`rclone/rclone:<ver>@…`, uid 65532, `HOME=/tmp`), emptyDir `/backup` (`sizeLimit: 2Gi`) and `/tmp`, read-only rootfs, memory 128Mi each.
  - CronJob `heartbeat`: `*/5 * * * *`, `concurrencyPolicy: Forbid`, `backoffLimit: 0`, image `curlimages/curl:<ver>@…`, uid 65532, 16Mi. `heartbeat.sh`: empty `HEALTHCHECKS_URL` → `heartbeat: HEALTHCHECKS_URL unset, skipping` and exit 0; else `curl -fsS -m 10 --retry 3 "$HEALTHCHECKS_URL"` and exit with its code.
  - `scripts/pg-restore.sh --db <name> [--stamp <STAMP>|latest] [--target <db>] [--overwrite] [--namespace data] [--deadline-s 600]`. The default target is `<db>_restore_check`. If target == source without `--overwrite`, it exits 1. It renders `scripts/templates/pg-restore-job.yaml` with `yq` (name `pg-restore-<db>-<rand>`): initContainer `fetch` (rclone, picks the newest `pg/*/` when `latest`) and container `restore` (postgres image: `dropdb --if-exists` + `createdb` for a scratch target, or for `--overwrite` `pg_restore --clean --if-exists`, then `pg_restore --no-owner --role=<owner> -d <target>`). Owner map (from `init-db.sh`): `orders→orders, notify→notify, reading_tuvi→reading, identity→identity, payment→payment`. The script applies the Job, waits with a deadline for `Complete` or `Failed`, prints the logs and exits accordingly.
  - `scripts/e2e-backup.sh` (runs after the observability checks):
    1. Scale all `apps` Deployments to 0 and wait until no app pods remain, so the DB is quiescent and counts are deterministic.
    2. Record per-db exact row counts of every table in schema `public` (via `kubectl exec postgres-0 -- psql`).
    3. `kubectl create job --from=cronjob/pg-backup pg-backup-e2e`, then wait Complete.
    4. For each db, `pg-restore.sh --db <db> --stamp latest`, then compare the counts of `<db>_restore_check` to step 2 (`OK backup-restore-<db>`). It also asserts `orders` has ≥ 1 row in `orders`.
    5. `kubectl create job --from=cronjob/heartbeat hb-e2e` → Complete (`OK heartbeat`). The e2e `HEALTHCHECKS_URL` is `http://minio.data.svc.cluster.local:9000/minio/health/live`.
    6. Patch `ops-secrets` to drop `HEALTHCHECKS_URL`, then run `hb-e2e-unset` → Complete with the log line `skipping` (`OK heartbeat-unset`).
  - e2e MinIO: Deployment + Service `minio` (ns `data`, `minio/minio:<ver>@…`, 256Mi, readiness `/minio/health/ready`, emptyDir data). `ops-secrets.env`: `S3_ENDPOINT=http://minio.data.svc.cluster.local:9000`, `S3_BUCKET=pg-backups`, `S3_REGION=us-east-1`, `S3_ACCESS_KEY_ID=local-test-minio`, `S3_SECRET_ACCESS_KEY=local-test-minio-secret`, `HEALTHCHECKS_URL=…/minio/health/live`, `HEALTHCHECKS_BACKUP_URL=` (empty). MinIO root credentials come from the same values. `rclone copy` creates the bucket on first write.

- [ ] **Step 1: Write `scripts/test/backup-script.test.sh`.** It puts stub `pg_dump`, `rclone` and `curl` executables first in `PATH`, each recording its argv to a file. Cases:
  - `backup_required_without_s3_fails` (exit 1, message);
  - `backup_optional_without_s3_skips` (exit 0, no `pg_dump` call);
  - `dump_calls_pg_dump_per_db` (5 calls, `-Fc --no-owner --no-acl`);
  - `upload_uses_stamp_prefix_and_prunes` (rclone argv has `pg/<STAMP>` then `delete --min-age 14d`);
  - `upload_pings_backup_heartbeat_when_set`;
  - `heartbeat_unset_skips`;
  - `heartbeat_failure_propagates` (stub curl exits 22 → exit 22).
- [ ] **Step 2: Run** the test → FAIL (scripts missing).
- [ ] **Step 3: Implement** the scripts, CronJobs, restore template and script, MinIO, `e2e-backup.sh` and the `k8s-e2e.sh` hook.
- [ ] **Step 4: Run** the test → PASS. Run the validator (CronJob pod templates pass `resources`, and the `secret-refs` check is satisfied by the e2e `ops-secrets`). Push. `k8s-e2e` shows `OK backup-restore-orders`, `…-notify`, `…-reading_tuvi`, `…-identity`, `…-payment`, `OK heartbeat` and `OK heartbeat-unset`. `bootstrap` still has `prod-platform` Healthy without `ops-secrets`.
- [ ] **Step 5: Commit** `feat(ops): daily pg_dump to S3-compatible storage, restore script, external heartbeat`

---

### Task 7: CD workflow: bump prod image tags to `sha-<commit>` via a GitHub App

**Findings that shape this task:** images are published only for the services whose path filter matched in that `ci` run (`publish` on push to main, tags `sha-<GITHUB_SHA>` and `main`). A bump must therefore cover exactly the images that exist for that SHA. Going live also needs every service pinned once, which is why `ci.yml` gains a manual `publish_all`.

**Files:**
- Create: `.github/workflows/cd.yml`, `scripts/cd-bump.sh`, `scripts/test/cd-bump.test.sh`, `scripts/testdata/cd-bump/kustomization.yaml`
- Modify: `.github/workflows/ci.yml` (`workflow_dispatch` input `publish_all: boolean`; every service job's `if` adds `|| inputs.publish_all`, and `publish` becomes `${{ github.ref == 'refs/heads/main' && (github.event_name == 'push' || github.event_name == 'workflow_dispatch') }}`)

**Interfaces:**
- Consumes: `overlays/prod/apps/kustomization.yaml` `images:` (Task 3), `scripts/k8s-validate.sh`.
- Produces:
  - `scripts/cd-bump.sh --file <kustomization> --sha <40-hex> [--repo <git dir>] <svc>...`. `svc` must be one of the 7. For each svc it reads the current `newTag`. If the current tag is `main`, or `sha-<X>` where `git merge-base --is-ancestor X <sha>`, it sets `newTag: sha-<sha>` and prints `BUMP <svc> <old> -> sha-<short>`. If the current SHA is equal it prints `SAME <svc>`. If the current SHA is newer or unrelated it prints `KEEP <svc>: current sha-<X> is not an ancestor of <sha>`. Invalid sha or unknown svc → exit 2. With no BUMP it prints `cd-bump: nothing to do`. It edits with `yq -i` and preserves comments and order.
  - `cd.yml`. Triggers: `on: workflow_run: {workflows: [ci], types: [completed], branches: [main]}` and `workflow_dispatch: {inputs: {sha: {required: true}}}`. `concurrency: {group: cd-main, cancel-in-progress: false}`, `permissions: contents: read, packages: read`. Jobs:
    1. `gate` (outputs `cd`, `verify`). It checks `github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push'` (or dispatch). `cd` is true when `vars.CD_APP_ID` and `secrets.CD_APP_PRIVATE_KEY` are both set. `verify` is true when `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` and `PROD_KUBECONFIG` are all set. Partial sets → `::error::`, exit 1. Missing → `::notice::` per the Gating rule.
    2. `bump` (`needs: gate`, `if: needs.gate.outputs.cd == 'true'`, outputs `pushed`, `bump_sha`):
       - `actions/create-github-app-token@<sha>` (`app-id: ${{ vars.CD_APP_ID }}`, `private-key: ${{ secrets.CD_APP_PRIVATE_KEY }}`);
       - checkout `main` with `fetch-depth: 0` and that token;
       - `docker/login-action` to GHCR (`GITHUB_TOKEN`);
       - for each of the 7 images, `docker buildx imagetools inspect ghcr.io/nguyentuan2686/<svc>:sha-<sha>`: existing ones form the list, absent ones print `SKIP <svc>: not published for <sha>`;
       - `cd-bump.sh`;
       - install pinned kubeconform as in `ci.yml`, then `scripts/k8s-validate.sh deploy/k8s/overlays/prod`;
       - assert that `git diff --name-only` is exactly `deploy/k8s/overlays/prod/apps/kustomization.yaml`;
       - commit as `<app-slug>[bot]` with message `deploy: <svc,…> to sha-<short> [skip ci]`, body listing the BUMP lines;
       - `git push`, with up to 3 attempts of `git pull --rebase` and re-running `cd-bump.sh`.
    3. `verify-deploy` (Task 8).

- [ ] **Step 1: Write `cd-bump.test.sh`** (temp git repo with commits A→B→C, fixture kustomization). Cases:
  - `main_tag_is_bumped`;
  - `ancestor_is_bumped` (current `sha-A`, new C);
  - `older_sha_is_not_applied` (current `sha-C`, new B → KEEP, file unchanged) (Review Focus 1);
  - `same_sha_is_noop`;
  - `unpublished_service_keeps_tag` (only `order-svc` passed → the other 6 unchanged byte for byte);
  - `invalid_sha_exits_2`;
  - `unknown_service_exits_2`;
  - `comments_preserved`.
- [ ] **Step 2: Run** the test (locally in `alpine/k8s:1.33.4` + `apk add git`) → FAIL.
- [ ] **Step 3: Implement** `cd-bump.sh`, `cd.yml` (`bump` job) and the `ci.yml` dispatch input.
- [ ] **Step 4: Run** the test → PASS. actionlint and check-action-pins → clean. After merge, the `cd` run on `main` must show the `::notice::CD skipped: CD_APP_ID not set …` annotation and conclude `success`. Read it with `gh run list --workflow cd.yml -L 1` and `gh run view <id>`.
- [ ] **Step 5: Commit** `feat(cd): bump prod image tags to sha-<commit> via GitHub App, gated on secrets`

---

### Task 8: verify-deploy with auto-rollback, go-live runbook, ADR, CLAUDE.md

**Decision (refines author call 8):** verify-deploy is a job inside `cd.yml` after `bump`, because a separate `workflow_run` workflow cannot see whether a bump was pushed. Since servicelb is off and the firewall is closed, it reaches the internal ingress by `kubectl port-forward svc/traefik` over the Tailscale-reachable API, with `Host: APP_DOMAIN`.

**Files:**
- Create: `scripts/deploy-rollback.sh`, `scripts/test/{deploy-rollback.test.sh,smoke-web-host.test.sh}`, `docs/runbooks/go-live.md`, `docs/adr/0005-gitops-cd-and-ops.md`
- Modify: `scripts/verify-deploy.sh` (smoke + rollback hooks), `scripts/smoke-web.sh` (optional `SMOKE_HOST_HEADER` env sent as `Host:` on every curl), `.github/workflows/cd.yml` (job `verify-deploy`), `CLAUDE.md`, `infra/bootstrap/README.md` (points to the runbook)

**Interfaces:**
- Consumes: `bump` outputs `pushed` and `bump_sha` (Task 7), `verify-deploy.sh --app/--revision` (Task 4), `public-url` and `seal-secrets.sh` (Task 3), `pg-restore.sh` (Task 6).
- Produces:
  - `scripts/verify-deploy.sh` (without `--no-smoke`): after the Argo checks for `prod-platform,prod-tunnel,prod-apps`, it port-forwards `kube-system/svc/traefik` to `127.0.0.1:18080` (wait_url deadline). It then runs `SMOKE_HOST_HEADER=<APP_DOMAIN from overlays/prod/domain/domain.yaml> scripts/smoke-web.sh http://127.0.0.1:18080` and prints `OK smoke-web`. Exit codes: 0 when everything passed, 1 on a failed check.
  - `scripts/deploy-rollback.sh --sha <bump sha> [--repo .] [--push]`. It refuses (exit 2) when the commit's changed files are not all under `deploy/k8s/overlays/`, or its message does not start with `deploy: `. Otherwise it runs `git revert --no-edit <sha>`, amends the message to `revert: deploy sha-<short> failed verify-deploy [skip ci]`, and pushes with `--push`. On a revert conflict it runs `git revert --abort`, exits 3 and prints `ROLLBACK: conflict, manual action needed`.
  - `cd.yml` job `verify-deploy`: `needs: [gate, bump]`, `if: needs.bump.outputs.pushed == 'true' && needs.gate.outputs.verify == 'true'`. If `pushed == 'true'` and `verify != 'true'`, a step prints `::warning::deployed without verification: Tailscale/kubeconfig secrets not set`. Steps:
    1. `tailscale/github-action@<sha>` (`oauth-client-id`, `oauth-secret`, `tags: tag:ci`);
    2. write `PROD_KUBECONFIG` to `$RUNNER_TEMP/kubeconfig` (mode 600, `KUBECONFIG` env);
    3. install pinned kubectl (as `_k8s-e2e.yml`);
    4. `scripts/verify-deploy.sh --app prod-platform,prod-tunnel,prod-apps --revision <bump_sha>`;
    5. on failure: App token, `scripts/deploy-rollback.sh --sha <bump_sha> --push`, then `gh issue create --title "deploy failed: sha-<short>" --label deploy-failed --body` with the run URL and the last 100 lines of verify output. The label is created if missing.
    The job fails after a rollback, so the failure is visible.
  - `docs/runbooks/go-live.md`, in this order and with exact commands:
    1. **What Tuấn provides:**
       - an Ubuntu 24.04 host (≥ 8 GB; at 8 GB lower retention and the lgtm memory as spec §16.3 says);
       - a Tailscale account with an auth key for the host and an OAuth client with `tag:ci`;
       - a GitHub App installed on the repo with `contents: write` and `issues: write`, allowed to bypass branch protection for `deploy/k8s/overlays/**`;
       - a Telegram bot token + chat id;
       - a Cloudflare R2 bucket (or any S3) + access key;
       - Healthchecks.io checks (heartbeat every 5 min, backup daily);
       - optionally a domain on Cloudflare;
       - GHCR packages set to public (or a pull secret; public is assumed).
    2. Run `sudo TS_AUTHKEY=… infra/bootstrap/bootstrap.sh` on the host, then `infra/bootstrap/verify.sh`.
    3. Back up the sealing key (`kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml`) to Tuấn's password manager. Never commit it.
    4. Run `kubeseal --fetch-cert > deploy/k8s/overlays/prod/sealed/sealing-cert.crt`. Fill `secrets.local/*.env` with real values, including `alerting-secrets`, `ops-secrets` and `cloudflared-secrets` when needed. Run `scripts/seal-secrets.sh --cert …`, then `bash scripts/k8s-validate.sh --require-sealed deploy/k8s/overlays/prod`.
    5. Set `BACKUP_REQUIRED=1`. Optionally set `APP_DOMAIN` in `overlays/prod/domain/domain.yaml` and switch `tunnel/` to `cloudflared-named`. Open a PR and merge.
    6. Set GitHub secrets/vars (`CD_APP_ID`, `CD_APP_PRIVATE_KEY`, `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `PROD_KUBECONFIG` with `server: https://<TS_HOSTNAME>:6443`).
    7. Run `gh workflow run ci.yml --ref main -f publish_all=true` to pin every image to one SHA via CD. Watch `cd` → `verify-deploy` green.
    8. Run `scripts/public-url` and check that alerts reach Telegram (Grafana "Test" button on the contact point). Restore drill: `scripts/pg-restore.sh --db orders --stamp latest`.
    9. Rollback and migration: re-run bootstrap with `SEALED_SECRETS_KEY_DIR`, then restore the databases.
  - ADR 0005 records:
    - the app-of-apps slices and waves;
    - cloudflared in GitOps and the quick/named components;
    - host hardening (no servicelb, ClusterIP Traefik, ufw, Tailscale-only admin);
    - CD bumps only published images, with the ancestor guard;
    - verify-deploy via port-forward, with rollback by revert;
    - Telegram rendered only when configured;
    - backup and heartbeat design;
    - the single-node assumption of the `k8s_cluster` receiver.
  - CLAUDE.md:
    - Repo map: `deploy/argocd/{root.yaml,apps/}`, `deploy/k8s/overlays/prod/{platform,tunnel,apps,sealed}`, `deploy/k8s/components/`, `infra/bootstrap/`.
    - Commands: `scripts/seal-secrets.sh`, `scripts/public-url`, `scripts/pg-restore.sh`, `scripts/alerts-promtool.sh`; "bootstrap runs only in CI job `bootstrap` or on the prod host, never on the dev machine".
    - CI jobs: `ops-scripts`, `bootstrap`; workflow `cd`.

- [ ] **Step 1: Write `deploy-rollback.test.sh`** (temp repo; bump commit by a bot identity touching `deploy/k8s/overlays/prod/apps/kustomization.yaml`, then an unrelated later commit). Cases: `reverts_only_the_bump` (later commit kept, file content back to the pre-bump tags, message as specified); `refuses_commit_outside_overlays` (exit 2); `refuses_non_deploy_message` (exit 2); `conflict_aborts_cleanly` (a later commit edits the same line → exit 3, worktree clean). Also add `scripts/test/smoke-web-host.test.sh`, which puts a stub `curl` first in `PATH` that records its argv and prints canned bodies. It has two cases: `host_header_sent_when_set` checks that every recorded call has `-H "Host: x"` when `SMOKE_HOST_HEADER=x`, and `no_host_header_by_default` checks that none does.
- [ ] **Step 2: Run** the tests → FAIL.
- [ ] **Step 3: Implement** the rollback script, the verify-deploy smoke part, the `smoke-web.sh` env, the `cd.yml` job, both runbooks, the ADR and the CLAUDE.md edits.
- [ ] **Step 4: Run** all `scripts/test/*.test.sh`, shellcheck, actionlint and check-action-pins → PASS. `bash scripts/smoke-web.sh` still works unchanged without the env, checked by reading the diff (CI `k8s-e2e` runs it). Push. On the PR, `ops-scripts`, `k8s-manifests`, `k8s-e2e` and `bootstrap` must be green. After merge, the `cd` run on `main` must show both skip notices and conclude `success`.
- [ ] **Step 5: Commit** `feat(cd): verify-deploy with auto-rollback, go-live runbook, ADR 0005`

---

## Self-review notes

- **Spec coverage:**
  - §14.5 bootstrap (k3s, Argo CD, Sealed Secrets, cloudflared) and GitOps CD: Tasks 3, 4 and 7.
  - §14.6 verify-deploy: Task 8. Alerts: Task 5. Backup: Task 6. The retro skill already exists. The `new-service` skill is deferred (header).
  - §7 CD 1–4: Tasks 7–8. `[skip ci]`, Argo sync, readiness (existing probes plus the Task 2 gates), revert + issue.
  - §8 Tailscale admin network: Tasks 4 and 8.
  - §10 alerts, heartbeat and retention: Tasks 5–6. "Deploy fail" is covered twice: the Argo `deploy-failed` rule and the verify-deploy issue.
  - §11 Sealed Secrets, GitHub App limited to overlays, no public ports: Tasks 3, 4 and 7.
  - §12 `APP_DOMAIN` as one overlay variable, migration via bootstrap + sealing-key restore + `pg-restore.sh`: Tasks 3, 4, 6 and 8.
  - §16.1 quick tunnel, `public-url`, verify via the internal ingress: Tasks 3 and 8. §16.3 both architectures: Task 4 matrix. §16.4 Telegram: Task 5.
- **Names used across tasks:** `secrets.tsv`, `domain.yaml`/`APP_DOMAIN`, `prod-{sealed,platform,tunnel,apps}`, `GITOPS_REVISION`/`GITOPS_CI_MODE`, `scripts/{seal-secrets.sh,public-url,verify-deploy.sh,deploy-rollback.sh,cd-bump.sh,pg-restore.sh,e2e-backup.sh,alerts-promtool.sh,check-action-pins.sh}`, `ops-secrets`/`alerting-secrets`/`cloudflared-secrets`, `BACKUP_REQUIRED`, the CI jobs `ops-scripts`/`bootstrap` and the `cd` jobs `gate`/`bump`/`verify-deploy`. They are consistent between the Interfaces blocks and the steps.
- **Proportion:** 8 tasks, each with its own test cycle. No code bodies, only signatures, exact values, exact expressions, and the commands that prove each task.
