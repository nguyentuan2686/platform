# 5. GitOps CD and operations on a single host

Date: 2026-11-27

## Status
Accepted (plan 4a in `docs/superpowers/plans/2026-11-26-plan-4a-gitops-ops-host-agnostic.md`)

## Context
ADR 0004 gave us Kustomize manifests that run in kind in CI. Plan 4a has to run them for real on one Ubuntu 24.04 host, which the owner may move between providers (the first is a GCP free-trial VM). The host may be x86 or ARM. There is no inbound network and no human review on merges. Deploys, alerts and backups must therefore work unattended, and they must be safe when the secrets they need are not set up yet.

## Decision
- **App-of-apps.**
  - The root Application `root` (path `deploy/argocd/apps`) owns the AppProject `prod` (wave -2) and four children, all tracking `main`: `prod-sealed` (wave -1: namespaces + SealedSecrets), `prod-platform` (wave 0: Postgres, Redpanda, observability, backup), `prod-tunnel` and `prod-apps` (wave 1).
  - The children run automated prune + selfHeal with ServerSideApply.
  - A Lua health check in `argocd-cm` makes the waves wait for child health.
  - The AppProject blacklists plain `Secret`: only SealedSecrets come from Git.
  - Only bootstrap may override the revision (`GITOPS_REVISION`), which CI uses to sync the PR commit.
- **cloudflared in GitOps.**
  - The tunnel is an Argo slice (`prod-tunnel`), built from one of two Kustomize components: `cloudflared-quick` (random `trycloudflare.com` URL, no account; the default until go-live) or `cloudflared-named` (a Cloudflare named tunnel, `TUNNEL_TOKEN` from `cloudflared-secrets`).
  - `APP_DOMAIN` is set once, in `overlays/prod/domain/domain.yaml`. Kustomize replacements copy it into the Ingress host, the web/identity origins, `MAIL_FROM` and the quick tunnel's Host header.
- **Host hardening.**
  - k3s runs with `--disable=servicelb`, and Traefik is a `ClusterIP` Service. NodePorts answer on loopback only.
  - ufw denies incoming traffic and is enabled before k3s is installed. It allows `lo`, `tailscale0` and the pod/service CIDRs.
  - Admin access (SSH, kube API on 6443) goes over Tailscale only. Public SSH is closed only on explicit opt-in (`BOOTSTRAP_CLOSE_PUBLIC_SSH=1`) and only while Tailscale is connected.
  - Public traffic arrives only through the outbound tunnel. Bootstrap pins every artifact by version + sha256 and is idempotent (a second run reports 0 changes, checked in CI on x86 and ARM).
- **CD bumps only published images, never backwards.**
  - `cd.yml` runs after a green `ci` run for a push or dispatch on `main` of this repo. It checks the triggering run's fields and never checks out its head.
  - For each image that ci actually published as `sha-<commit>` (GHCR inspect), `scripts/cd-bump.sh` sets the tag in `overlays/prod/apps/kustomization.yaml`. The ancestor guard: a current tag that is not an ancestor of the new commit, or a hand pin, is kept, so concurrent runs converge to the newest tag per service.
  - The commit is made by a GitHub App installation token, with a CI-skip marker, and may change exactly that one file. Concurrency is per commit, so a burst of merges never drops a pending deploy.
- **verify-deploy via port-forward, rollback by revert.**
  - A third job in `cd.yml` runs `needs: [gate, bump]`, because only that run knows whether a bump was pushed.
  - It joins the tailnet as `tag:ci` and waits until `prod-platform`, `prod-tunnel` and `prod-apps` are all Synced + Healthy at the bump commit in the same poll. A newer commit on `main` also counts (`--allow-descendant`).
  - It then port-forwards `kube-system/svc/traefik` and runs `scripts/smoke-web.sh` with `Host: <APP_DOMAIN>`. It tests the internal ingress, not the tunnel: the tunnel URL is random until go-live, and the tunnel is a separate Argo app whose health is already checked.
  - On failure, `scripts/deploy-rollback.sh` reverts the bump commit on `main`. It accepts only a single-parent `deploy: ` commit that changes only `deploy/k8s/overlays/`, and aborts cleanly on a conflict (exit 3). The job then opens a `deploy-failed` issue and fails.
- **Gating.** Every feature that needs secrets prints a `::notice::... skipped` and succeeds while none of them is set, and fails on a partial set: CD, verify-deploy, Telegram, backup, heartbeat, the named tunnel. A deploy without verification is a `::warning::`.
- **Telegram only when configured.**
  - Grafana alert rules are always provisioned (folder `Platform`, `noDataState: OK`), and presence probes keep a dead metric from hiding an alert.
  - The `telegram` contact point and the policy are rendered by an init container only when `alerting-secrets` has a token, into a memory-backed volume. The token never enters the Grafana environment.
- **Backup and heartbeat.**
  - The CronJob `pg-backup` (03:00 Asia/Ho_Chi_Minh) runs `pg_dump -Fc` of the 5 databases to S3-compatible storage under `pg/<UTC stamp>/<db>.dump`. It keeps 14 days, pruning only after a successful upload, and pings a Healthchecks.io check.
  - Credentials are split per container (the dump container never sees S3 keys, the upload container never sees DB passwords).
  - `BACKUP_REQUIRED=0` skips until go-live; `1` makes a missing config a failure.
  - `scripts/pg-restore.sh` restores into `<db>_restore_check` by default and refuses production/system targets without `--overwrite`.
  - A `heartbeat` CronJob pings Healthchecks.io every 5 minutes as an external dead-man's switch. If the host or cluster dies, the missing ping alerts, which in-cluster alerting cannot do.
- **Single-node assumption.** The collector's `k8s_cluster` receiver and the node-level metrics (disk usage, restarts) assume one node: a single collector instance sees the whole cluster and the only filesystem. A second node needs a leader-elected cluster receiver and per-node filesystem metrics.

## Consequences
- Deploys are hands-off and reversible by Git. The cost is the GitHub App bypass on `main`, which GitHub cannot limit to a path. The limit is enforced by the workflow and the rollback script instead.
- verify-deploy depends on Tailscale and a kubeconfig secret held in GitHub. A leaked `PROD_KUBECONFIG` reaches the API only from the tailnet, and the runbook offers a least-privilege ServiceAccount instead of cluster-admin.
- Argo CD polls Git (no inbound webhook), so a deploy takes up to a few minutes to start.
- A rollback can conflict when a later bump changed the same line. That case needs a human (issue + exit 3).
- Losing the sealing key makes every SealedSecret unreadable. The key is backed up by hand (go-live step 3). A host move is bootstrap + key restore + `pg-restore.sh`, documented in `docs/runbooks/go-live.md`.
- Single host, single Postgres, single lgtm pod: no HA. The backups and the external heartbeat are the safety net.
