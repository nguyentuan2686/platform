# Runbook: go-live (first prod host, CD, verify-deploy, rollback, host move)

Audience: Tuấn, at a terminal on the dev machine (Git Bash) and over SSH on the prod host. Follow the steps in order. Every step is safe to repeat.

Rules for the whole runbook:
- Secret values go only into a password manager, `deploy/k8s/overlays/prod/secrets.local/` (gitignored, on the machine that seals) or GitHub secrets. Never into Git, an issue, a PR, or a chat with Claude.
- Read secrets with `read -rs NAME` so they stay out of shell history, and pass them on with `--preserve-env`, never as a command-line argument.
- The dev machine never runs bootstrap, k3s or Argo CD. Bootstrap runs only on the prod host, or in the CI job `bootstrap`.

The first host is the GCP free-trial VM `tuvi-prod-1`:
- zone `asia-southeast1-b`, Ubuntu 24.04 x86, 2 vCPU / 12 GB, 40 GB disk;
- ephemeral public IP;
- VPC firewall: only the defaults (SSH 22, ICMP, internal). Do **not** add http/https rules: public traffic arrives through the outbound Cloudflare tunnel, so nothing listens publicly;
- the free trial ends on **2027-02-24** (90 days; check Billing in the GCP console). Plan [the host move](#9-rollback-and-moving-to-a-new-host) before that date.

## 1. What Tuấn provides

| Item | Used for | Where it ends up |
|---|---|---|
| An Ubuntu 24.04 host, x86 or ARM, **≥ 8 GB RAM** (`tuvi-prod-1` has 12 GB). At 8 GB, lower the retention and the lgtm memory as spec §16.3 says: in `deploy/k8s/platform/observability/lgtm.yaml`, set `PROMETHEUS_EXTRA_ARGS` to `--storage.tsdb.retention.time=7d`, `-store.retention` and both Tempo `block-retention` values to `72h`, and the lgtm memory request and limit to `768Mi`. | everything | — |
| A **Tailscale** account (MagicDNS on). Create two things: (a) a single-use, pre-approved **auth key** for the host, tagged `tag:prod`, and (b) an **OAuth client** with the `auth_keys` write scope and tag `tag:ci`. ACL: the snippet below. | admin access (SSH, kube API) without public ports; CI reaches the API | (a) typed once on the host (b) GitHub secrets `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` |
| A **GitHub App** installed on this repo only. Repository permissions `Contents: Read and write` and `Issues: Read and write`, nothing else, no webhook. Note its **App ID** (a number on the App's settings page, not the Client ID) and generate a **private key** (`.pem`). If `main` is protected (branch protection or a ruleset that requires PRs or status checks), add the App as a **bypass actor**. GitHub cannot limit a bypass to a path: the limit to `deploy/k8s/overlays/**` is enforced by `cd.yml` (the bump commit may change only `deploy/k8s/overlays/prod/apps/kustomization.yaml`) and by `scripts/deploy-rollback.sh` (refuses any commit outside `deploy/k8s/overlays/`). | CD bump commits, rollback reverts, `deploy-failed` issues | variable `CD_APP_ID`, secret `CD_APP_PRIVATE_KEY` |
| A **Telegram** bot token (from @BotFather) and the chat id of the chat that receives alerts. | alerts (`docs/runbooks/alerts.md`) | `alerting-secrets` |
| A **Cloudflare R2** bucket (or any S3-compatible storage) and an access key limited to that bucket: endpoint, bucket, region (`auto` for R2), key id, secret. | daily `pg-backup`, restores | `ops-secrets` |
| **Healthchecks.io**: two checks. `heartbeat` with period 5 min, grace 5 min. `backup` with period 1 day, grace 2 h (the backup runs 03:00 Asia/Ho_Chi_Minh). Copy both ping URLs. | dead-man's switch for the host and for backups | `ops-secrets` (`HEALTHCHECKS_URL`, `HEALTHCHECKS_BACKUP_URL`) |
| Optional: a **domain on Cloudflare** and a **named tunnel** (Zero Trust -> Networks -> Tunnels -> create, connector "cloudflared"; copy the tunnel token). Add a public hostname `<APP_DOMAIN>` -> `http://traefik.kube-system.svc.cluster.local:80`. Without a domain, the quick tunnel gives a random `*.trycloudflare.com` URL. | the public URL | `cloudflared-secrets` (`TUNNEL_TOKEN`), `domain.yaml` |
| **GHCR packages public**: for each of the 7 images (`order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc web`), Package settings -> Change visibility -> Public. Public is assumed. A private package needs an image pull secret, which is not set up. | the host pulls images | — |
| App providers (not needed to deploy, needed for real users): an SMTP relay for magic-link mail, Groq and/or Gemini API keys, a SePay account, a Google OAuth client. See [Known gaps](#known-gaps-before-real-users). | identity-svc, reading-tuvi, payment-svc | the app secrets in `secrets.tsv` |

Tailscale ACL (admin console -> Access controls). `tag:prod` is the host, `tag:ci` the GitHub runner, which may reach only the kube API:

```json
{
  "tagOwners": { "tag:prod": ["autogroup:admin"], "tag:ci": ["autogroup:admin"] },
  "grants": [
    { "src": ["autogroup:admin"], "dst": ["tag:prod"], "ip": ["*"] },
    { "src": ["tag:ci"], "dst": ["tag:prod"], "ip": ["tcp:6443"] }
  ]
}
```

Tagged nodes have no key expiry, so the host does not drop off the tailnet after 180 days.

## 2. Bootstrap the host

On the host, over the GCP browser SSH or `gcloud compute ssh tuvi-prod-1 --zone asia-southeast1-b`:

```sh
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/nguyentuan2686/platform.git && cd platform
read -rs TS_AUTHKEY && export TS_AUTHKEY          # paste the tag:prod auth key; it is not echoed
sudo --preserve-env=TS_AUTHKEY infra/bootstrap/bootstrap.sh
unset TS_AUTHKEY
sudo infra/bootstrap/verify.sh                    # every line OK
sudo infra/bootstrap/bootstrap.sh                 # second run: last line `bootstrap: 0 changed`
```

The node joins the tailnet as `platform-prod` (`TS_HOSTNAME`). Public SSH stays open. Close it only as `infra/bootstrap/README.md` "Closing public SSH" says, after SSH over the tailnet works from your own device. The same README has the GCP recovery path if you lock yourself out.

Argo CD now syncs `main`. Until step 4, `prod-sealed` has nothing to decrypt and the apps cannot start; that is expected.

## 3. Back up the sealing key

On the host:

```sh
sudo k3s kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > sealing-key.yaml
```

Put `sealing-key.yaml` into your password manager as an attachment, then `shred -u sealing-key.yaml`. **Never commit it.** Without this key, every SealedSecret in Git is unreadable on a new host. The controller adds a new key every 30 days. Repeat this backup after each rotation (monthly), or at least before a host move.

## 4. Seal the prod secrets

Run on the machine that holds the plaintext (the dev machine is fine: sealing is offline and needs only the public cert). First get the cert. On the host:

```sh
sudo k3s kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key=active \
  -o jsonpath='{.items[0].data.tls\.crt}' | base64 -d     # same as `kubeseal --fetch-cert`
```

Save the output as `deploy/k8s/overlays/prod/sealed/sealing-cert.crt` in your checkout. With kubectl access over the tailnet, `kubeseal --fetch-cert > deploy/k8s/overlays/prod/sealed/sealing-cert.crt` does the same.

Then fill one file per secret in `deploy/k8s/overlays/prod/secrets.local/<name>.env` (`KEY=value` lines). The names and keys are in `deploy/k8s/overlays/prod/secrets.tsv`:
- The 7 required app/data secrets. Generate passwords and tokens with `openssl rand -hex 32`, and the AES keys (`QA_ENC_KEY`, `PROFILE_ENC_KEY`) with `openssl rand -base64 32`. `INTERNAL_API_TOKEN` must be the same value in every secret that has it, and the `*_DB_PASSWORD` values in `postgres-secrets` must match `DB_PASSWORD` of the matching service. A key ending in `?` may be empty.
- `alerting-secrets`: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.
- `ops-secrets`: the `S3_*` values and both Healthchecks URLs.
- `cloudflared-secrets`: `TUNNEL_TOKEN`, only with a named tunnel.

On Linux (or the prod host) run the scripts directly. On the dev machine (Windows; kubeseal auto-install is Linux only), run them in the pinned tools image:

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 sh -c '
  apk add -q git && git config --global --add safe.directory /w &&
  bash scripts/seal-secrets.sh --cert deploy/k8s/overlays/prod/sealed/sealing-cert.crt &&
  bash scripts/k8s-validate.sh --require-sealed deploy/k8s/overlays/prod'   # every required secret is sealed
git status --short deploy/k8s/overlays/prod      # only sealed/*.sealedsecret.yaml, sealed/kustomization.yaml, sealing-cert.crt
```

`seal-secrets.sh` refuses test-looking values and never prints a value. Keep `secrets.local/` only as long as you need it, or move it into the password manager.

## 5. Go-live config PR

On a branch, together with the sealed files from step 4:
- `deploy/k8s/overlays/prod/platform/kustomization.yaml`: set both `BACKUP_REQUIRED` values to `"1"`. Do this only once `ops-secrets` is sealed; with `"1"`, a missing S3 config fails the backup job and alerts.
- Optional, with a domain: set `APP_DOMAIN` in `deploy/k8s/overlays/prod/domain/domain.yaml`. In `deploy/k8s/overlays/prod/tunnel/kustomization.yaml`, replace `../../../components/cloudflared-quick` with `../../../components/cloudflared-named` (needs the sealed `cloudflared-secrets`).

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh deploy/k8s/overlays/prod
git checkout -b feat/go-live && git add deploy/k8s/overlays/prod && git commit -m "feat(deploy): go-live secrets and config"
git push -u origin feat/go-live && gh pr create --fill
```

Merge when CI is green. Never put a CI-skip marker in the PR title or body (see `docs/lessons/2026-11-27-skip-ci-marker-in-commit-body.md`).

## 6. GitHub secrets and variables

The kubeconfig for CD must point at the tailnet name. k3s already has `platform-prod` as a TLS SAN. On the host:

```sh
sudo sed 's#https://127.0.0.1:6443#https://platform-prod:6443#' /etc/rancher/k3s/k3s.yaml > prod-kubeconfig
```

This is a cluster-admin credential. A least-privilege alternative that verify-deploy is enough for:

```sh
sudo k3s kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata: {name: cd-verify, namespace: kube-system}
---
apiVersion: v1
kind: Secret
metadata: {name: cd-verify-token, namespace: kube-system, annotations: {kubernetes.io/service-account.name: cd-verify}}
type: kubernetes.io/service-account-token
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: cd-verify, namespace: argocd}
rules: [{apiGroups: [argoproj.io], resources: [applications], verbs: [get]}]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: cd-verify, namespace: argocd}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: cd-verify}
subjects: [{kind: ServiceAccount, name: cd-verify, namespace: kube-system}]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: cd-verify-portforward, namespace: kube-system}
rules:
  - {apiGroups: [""], resources: [services, pods], verbs: [get, list]}
  - {apiGroups: [""], resources: [pods/portforward], verbs: [create]}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: cd-verify-portforward, namespace: kube-system}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: cd-verify-portforward}
subjects: [{kind: ServiceAccount, name: cd-verify, namespace: kube-system}]
EOF
TOKEN=$(sudo k3s kubectl -n kube-system get secret cd-verify-token -o jsonpath='{.data.token}' | base64 -d)
sudo k3s kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > ca.crt
KUBECONFIG=prod-kubeconfig kubectl config set-cluster prod --server=https://platform-prod:6443 \
  --certificate-authority=ca.crt --embed-certs=true
KUBECONFIG=prod-kubeconfig kubectl config set-credentials cd-verify --token="$TOKEN"
KUBECONFIG=prod-kubeconfig kubectl config set-context prod --cluster=prod --user=cd-verify
KUBECONFIG=prod-kubeconfig kubectl config use-context prod
unset TOKEN; rm -f ca.crt
```

Use either the admin file or this one as `prod-kubeconfig`, not both. Then, from a machine with `gh` logged in (copy `prod-kubeconfig` there over the tailnet, e.g. `scp`):

```sh
gh variable set CD_APP_ID --body <App ID>                        # the number, not the Client ID
gh secret set CD_APP_PRIVATE_KEY < <app>.private-key.pem
read -rs V && gh secret set TS_OAUTH_CLIENT_ID --body "$V"; unset V
read -rs V && gh secret set TS_OAUTH_SECRET --body "$V"; unset V
gh secret set PROD_KUBECONFIG < prod-kubeconfig
shred -u prod-kubeconfig <app>.private-key.pem                   # after storing the key in the password manager
```

Each feature takes all of its secrets or none. A partial set fails the `cd` gate with `::error::`. CD alone (`CD_APP_ID` + `CD_APP_PRIVATE_KEY`) deploys without verification, and the run then shows `::warning::deployed without verification`.

## 7. First deploy

`main` has the tags `main` until CD pins them. Publish every image for one commit, and let CD pin all 7:

```sh
gh workflow run ci.yml --ref main -f publish_all=true
gh run watch "$(gh run list --workflow ci.yml -L 1 --json databaseId -q '.[0].databaseId')"
gh run watch "$(gh run list --workflow cd -L 1 --json databaseId -q '.[0].databaseId')"
```

Expected in the `cd` run:
- `bump`: 7 `BUMP` lines and a push of `deploy: ... to sha-<short>`.
- `verify-deploy`: `OK argo-prod-platform`, `OK argo-prod-tunnel`, `OK argo-prod-apps`, `OK port-forward`, `OK smoke-web`.

Argo CD polls Git every 3 minutes, so verify-deploy usually takes 3–10 minutes. Later merges deploy the same way, but only for the services whose images that merge published.

## 8. Check the live system

```sh
export KUBECONFIG=<an admin kubeconfig with server https://platform-prod:6443>   # or run on the host with sudo k3s kubectl
scripts/public-url                                   # the URL; open it, the home page says "Lão Thiên Cơ"
```

Alerts reach Telegram:
1. Open Grafana: `kubectl -n observability port-forward svc/lgtm 3000:3000`, then http://localhost:3000.
2. Go to Alerting -> Contact points -> `telegram` -> **Test**. A message must arrive in the chat.

Restore drill. Wait for one 03:00 backup, or run it once now with `kubectl -n data create job --from=cronjob/pg-backup pg-backup-now`. Then:
```sh
scripts/pg-restore.sh --db orders --stamp latest     # restores into orders_restore_check; exit 0 = restorable
```

Healthchecks.io shows `heartbeat` up within 5 minutes and `backup` after the first backup.

## 9. Rollback and moving to a new host

### Automatic rollback
When `verify-deploy` fails, CD does three things:
1. It reverts the bump commit on `main` (`revert: deploy sha-<short> failed verify-deploy`, with a CI-skip marker).
2. It opens an issue labelled `deploy-failed` with the run URL and the last 100 lines of verify-deploy.
3. It fails the run.

Argo CD then syncs the previous tags. Read the issue, fix the cause in a normal PR, and the next merge deploys again.

If the issue says `NOT rolled back: the revert conflicted`, a later commit changed the same image line. Revert by hand:
```sh
git revert <bump sha>     # resolve deploy/k8s/overlays/prod/apps/kustomization.yaml to the last good tags
```
Then open a PR. Never push to `main` yourself.

### Manual rollback to a known good commit
Pin one service back in a PR: set its `newTag` in `deploy/k8s/overlays/prod/apps/kustomization.yaml` to the previous `sha-<40 hex>`. CD never moves a tag backwards on its own. `scripts/cd-bump.sh` keeps a tag that is not an ancestor of the new commit, so your pin stays until a newer commit of that service is published.

### Move to a new host (GCP trial end, or a host failure)
1. **New host:** create an Ubuntu 24.04 host (≥ 8 GB). In Tailscale, remove the old `platform-prod` node first, or give the new one another `TS_HOSTNAME` and update `PROD_KUBECONFIG`.
2. **Final backup on the old host, if it still runs:**
   ```sh
   kubectl -n data create job --from=cronjob/pg-backup pg-backup-final
   kubectl -n data wait --for=condition=complete job/pg-backup-final --timeout=15m
   ```
   Without R2 (`ops-secrets` not set up), dump each database directly:
   ```sh
   for db in orders notify reading_tuvi identity payment; do
     kubectl -n data exec postgres-0 -- pg_dump -U postgres -Fc --no-owner --no-acl "$db" > "$db.dump"
   done
   ```
   Keep the dumps out of Git and off shared drives; they hold user data.
3. **Bootstrap with the old sealing key.** Unpack `tls.crt` and `tls.key` of each key from the `sealing-key.yaml` in your password manager into a directory (the newest key is enough for current seals). Then, on the new host:
   ```sh
   read -rs TS_AUTHKEY && export TS_AUTHKEY
   sudo --preserve-env=TS_AUTHKEY SEALED_SECRETS_KEY_DIR=<dir> infra/bootstrap/bootstrap.sh
   unset TS_AUTHKEY; sudo infra/bootstrap/verify.sh; shred -u <dir>/*
   ```
   The SealedSecrets in Git decrypt with the restored key. If that key is older than 30 days, the controller creates a new one at start. Then fetch the cert again (step 4), commit `sealing-cert.crt` in a PR, and back up the new key (step 3).
4. **Restore the databases** once `prod-platform` is Healthy and before users arrive. From R2:
   ```sh
   for db in orders notify reading_tuvi identity payment; do
     scripts/pg-restore.sh --db "$db" --stamp latest --target "$db" --overwrite
   done
   ```
   From local dumps:
   ```sh
   for db in orders notify reading_tuvi identity payment; do
     kubectl -n data exec -i postgres-0 -- pg_restore -U postgres --clean --if-exists --no-owner -d "$db" < "$db.dump"
   done
   kubectl -n apps rollout restart deploy          # services reconnect to the restored data
   ```
5. **Update and re-run the checks.** Update `PROD_KUBECONFIG` (step 6) if the name or CA changed: a new host has a new k3s CA, so the secret must be replaced in any case. Then `gh workflow run ci.yml --ref main -f publish_all=true` (step 7), and step 8.
6. **Shut down the old host.** Named tunnel: the new connector takes over the same hostname. Quick tunnel: the URL changes, see `scripts/public-url`.

## Known gaps before real users

These do not block deploying, verify-deploy or rollback. They block the real flows:
- **SMTP auth for magic links:** identity-svc has `MAIL_HOST`/`MAIL_PORT`/`MAIL_FROM` only, with no username/password/STARTTLS settings. An authenticated relay needs these settings added to identity-svc, or `SPRING_MAIL_*` keys added to `secrets.tsv`, before login mail works.
- **SePay webhook:** payment-svc serves `/webhooks/sepay`, but the prod Ingress routes only to web, and web has no public proxy for the webhook in prod. SePay cannot reach it yet.
- **web build-time origin:** `docs/runbooks/web.md` says the web image must be built with `--build-arg WEB_ORIGIN=https://<domain>`. CI publishes web without that build arg, so canonical URLs and the sitemap baked at build time are empty until the build passes `APP_DOMAIN`.
- **Google login:** follow `docs/runbooks/web.md` §2.3 (`OIDC_GOOGLE_CLIENT_ID`, `OIDC_REDIRECT_URIS`, `GOOGLE_LOGIN=1`).
