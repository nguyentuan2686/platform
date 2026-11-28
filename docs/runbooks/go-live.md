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
| A **GitHub App** installed on this repo only. Repository permissions `Contents: Read and write` and `Issues: Read and write`, nothing else, no webhook. Note its **App ID** (a number on the App's settings page, not the Client ID) and generate a **private key** (`.pem`). If `main` is protected (branch protection or a ruleset that requires PRs or status checks), add the App as a **bypass actor**. GitHub cannot limit a bypass to a path: the limit to `deploy/k8s/overlays/**` is enforced by `cd.yml` (the bump commit may change only `deploy/k8s/overlays/prod/apps/kustomization.yaml`) and by `scripts/deploy-rollback.sh` (refuses any commit outside `deploy/k8s/overlays/`). The same `Contents: write` lets the App force-move the tag `deploy-verified` (the last verified deploy; branch protection does not cover tags). If you ever add a tag ruleset, allow the App to update and force-push `deploy-verified`. | CD bump commits, rollback reverts, the `deploy-verified` tag, `deploy-failed`/`deploy-unverified`/`deploy-unrecorded` issues | GitHub Environment `prod`: variable `CD_APP_ID`, secret `CD_APP_PRIVATE_KEY` |
| A **Telegram** bot token (from @BotFather) and the chat id of the chat that receives alerts. | alerts (`docs/runbooks/alerts.md`) | `alerting-secrets` |
| A **Cloudflare R2** bucket (or any S3-compatible storage) and an access key limited to that bucket: endpoint, bucket, region (`auto` for R2), key id, secret. | daily `pg-backup`, restores | `ops-secrets` |
| **Healthchecks.io**: two checks. `heartbeat` with period 5 min, grace 5 min. `backup` with period 1 day, grace 2 h (the backup runs 03:00 Asia/Ho_Chi_Minh). Copy both ping URLs. | dead-man's switch for the host and for backups | `ops-secrets` (`HEALTHCHECKS_URL`, `HEALTHCHECKS_BACKUP_URL`) |
| **Required before real users:** a **domain on Cloudflare** and a **named tunnel** (Zero Trust -> Networks -> Tunnels -> create, connector "cloudflared"; copy the tunnel token). Add a public hostname `<APP_DOMAIN>` -> `http://traefik.kube-system.svc.cluster.local:80`. Without a domain, the quick tunnel gives a random `*.trycloudflare.com` URL that is a **read-only preview** only (see [Known gaps](#known-gaps-before-real-users)). | the public URL, login, payments, the SePay webhook | `cloudflared-secrets` (`TUNNEL_TOKEN`), `domain.yaml` |
| **GHCR packages public**: for each of the 7 images (`order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc web`), Package settings -> Change visibility -> Public. Public is assumed. A private package needs an image pull secret, which is not set up. | the host pulls images | — |
| App providers (not needed to deploy, needed for real users): an **SMTP relay** for magic-link mail (any provider with SMTP AUTH and STARTTLS on port 587: host, port, username, password; the sender domain `<APP_DOMAIN>` verified there with **SPF and DKIM** DNS records, or every relay rejects `no-reply@<APP_DOMAIN>`), Groq and/or Gemini API keys, a SePay account, a Google OAuth client. See [Known gaps](#known-gaps-before-real-users). | identity-svc, reading-tuvi, payment-svc | the app secrets in `secrets.tsv` (SMTP: `MAIL_USERNAME`, `MAIL_PASSWORD` in `identity-svc-secrets`); host and port in the go-live PR (§5) |
| Optional, recommended before real users: **Cloudflare Turnstile** on the login form (bot check). Not set up yet; the edge rate limits and the daily mail cap are the abuse guard until then. | login form | — |

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

Without a Tailscale account yet, skip the two `TS_AUTHKEY` lines: bootstrap prints `SKIP tailscale`, and CD later deploys without verification or rollback (`::warning::deployed without verification`). Adding Tailscale later = run bootstrap again with `TS_AUTHKEY`, then §6.

The node joins the tailnet as `platform-prod` (`TS_HOSTNAME`). Public SSH stays open. Close it only as `infra/bootstrap/README.md` "Closing public SSH" says, after SSH over the tailnet works from your own device. The same README has the GCP recovery path if you lock yourself out.

Argo CD now syncs `main`. Until step 4, `prod-sealed` has nothing to decrypt and the apps cannot start; that is expected.

## 3. Back up the sealing keys

These keys decrypt every SealedSecret. Without them, the secrets in Git are unreadable on a new host. They never go into Git, the repo checkout, a chat or a shared drive.

The controller adds a **new key every 30 days** and keeps the old ones. A SealedSecret stays bound to the key that was active when it was sealed: renewal never re-encrypts it. So the backup must hold **all** keys, and it must be **re-exported after each 30-day renewal**. A backup that misses a key cannot decrypt the seals made with that key. Set a monthly reminder (e.g. on the 1st) for this step.

On the host, write all keys into one file in your home directory (not into `platform/`), readable only by you:

```sh
cd ~ && umask 077
sudo k3s kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o json > ~/sealing-key.json
ls -l ~/sealing-key.json      # must show -rw------- and your user
python3 -c 'import json; print(len(json.load(open("sealing-key.json"))["items"]), "keys")'   # 1 at go-live, +1 per month
```

Move it into the password manager:
1. Copy it to your computer, into your Downloads folder (never into the repo folder). In the GCP browser SSH window, open the gear menu (top right) and choose **Download file**. Enter `/home/<your user>/sealing-key.json`. It lands in Downloads. Or, from a terminal with gcloud: `gcloud compute scp tuvi-prod-1:~/sealing-key.json ~/Downloads/ --zone asia-southeast1-b`.
2. In the password manager, create (or update) the entry "platform sealing keys" and attach the file. Replace the previous attachment: the new file holds every older key too. Bitwarden: New item -> Secure note -> Attachments. 1Password: New item -> Document.
3. Delete both copies. On your computer, delete the downloaded file and empty the Recycle Bin. On the host: `shred -u ~/sealing-key.json`.

The alternative to the monthly re-export is to turn automatic renewal off with the controller flag `--key-renew-period=0`. Then one key is used forever and the go-live backup stays complete. The trade-off: no key rotation at all, so a leaked key decrypts every past and future seal. This repo keeps renewal on, so the monthly re-export is required.

## 4. Seal the prod secrets

Run on the machine that holds the plaintext (the dev machine is fine: sealing is offline and needs only the public cert). First get the cert. On the host:

```sh
sudo k3s kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key=active \
  -o jsonpath='{.items[0].data.tls\.crt}' | base64 -d     # same as `kubeseal --fetch-cert`
```

Save the output as `deploy/k8s/overlays/prod/sealed/sealing-cert.crt` in your checkout. With kubectl access over the tailnet, `kubeseal --fetch-cert > deploy/k8s/overlays/prod/sealed/sealing-cert.crt` does the same.

Then fill one file per secret in `deploy/k8s/overlays/prod/secrets.local/<name>.env` (`KEY=value` lines). The names and keys are in `deploy/k8s/overlays/prod/secrets.tsv`:
- The 7 required app/data secrets. Generate passwords and tokens with `openssl rand -hex 32`, and the AES keys (`QA_ENC_KEY`, `PROFILE_ENC_KEY`) and the xem-số HMAC key `NUMBER_CHECK_HMAC_KEY` (in `reading-tuvi-secrets`) with `openssl rand -base64 32`. `INTERNAL_API_TOKEN` must be the same value in every secret that has it, and the `*_DB_PASSWORD` values in `postgres-secrets` must match `DB_PASSWORD` of the matching service. A key ending in `?` may be empty. `identity-svc-secrets` also holds the SMTP relay login `MAIL_USERNAME` and `MAIL_PASSWORD` (from the provider; never in a manifest). Both may stay empty for the quick-tunnel preview: login mail then fails with 503 until a relay is set.
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
- `deploy/k8s/overlays/prod/platform/kustomization.yaml`: set both `BACKUP_REQUIRED` values to `"1"` in the same PR that adds `sealed/ops-secrets.sealedsecret.yaml`, and never one without the other: `scripts/k8s-validate.sh` (check `prod-backup`) fails the PR otherwise. With `"1"`, a missing S3 config fails the backup job and alerts. Until then every nightly backup skips and the `backup-skipped` alert (warning) fires, because there is no off-host copy of the data.
- **Required before real users** (a domain and a named tunnel; the quick tunnel is a read-only preview):
  - set `APP_DOMAIN` in `deploy/k8s/overlays/prod/domain/domain.yaml`. That one file feeds the Ingress hosts, the runtime `WEB_ORIGIN`, `WEB_BASE_URL`, `MAIL_FROM` and, through `scripts/web-origin.sh` in `ci.yml`, the web image's build-time `WEB_ORIGIN`. A domain change republishes web, and CD bumps it;
  - in `deploy/k8s/overlays/prod/tunnel/kustomization.yaml`, replace `../../../components/cloudflared-quick` with `../../../components/cloudflared-named` (needs the sealed `cloudflared-secrets`);
  - in `deploy/k8s/overlays/prod/apps/patches/identity-svc.yaml`, set `MAIL_HOST` and `MAIL_PORT` to the SMTP relay (submission port 587 with STARTTLS; `MAIL_SMTP_AUTH` and `MAIL_STARTTLS` stay `"true"`). Verify the sender domain at the provider (SPF and DKIM records in Cloudflare DNS) first;
  - SePay: set the webhook URL to `https://<APP_DOMAIN>/webhooks/sepay` with API-key auth (`SEPAY_WEBHOOK_SECRET`). Only that exact path reaches payment-svc (Ingress `payment-webhook`, pathType Exact, 64 KiB body cap, rate limited); `/webhooks/paypal`, `/admin/*` and every other payment-svc path stay internal.
  - Receiving account: `SEPAY_BANK_CODE` (SePay/VietQR short name, e.g. `MBBank`), `SEPAY_ACCOUNT_NUMBER`, `SEPAY_ACCOUNT_NAME` live in the sealed `payment-svc-secrets` (an account number can be a phone number, which never goes into Git in plaintext). Empty = SePay orders answer 503.

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh deploy/k8s/overlays/prod
git checkout -b feat/go-live && git add deploy/k8s/overlays/prod && git commit -m "feat(deploy): go-live secrets and config"
git push -u origin feat/go-live && gh pr create --fill
```

Merge when CI is green. Never put a CI-skip marker in the PR title or body (see `docs/lessons/2026-11-27-skip-ci-marker-in-commit-body.md`).

## 6. GitHub secrets and variables

All CD and verify-deploy secrets live in the GitHub **Environment `prod`**, never as repository secrets. Repository secrets are readable by a workflow on any branch of this repo (a PR branch could mint a CD App token and push to `main`, or reach the cluster). An Environment that allows only `main` is readable only by jobs that run on `main` and declare `environment: prod` (in `cd.yml`: `gate`, `bump`, `verify-deploy`; `scripts/test/cd-gate.test.sh` fails any other job that names these secrets).

First create the Environment and restrict it to `main`. **You must do this yourself** (a repo setting, admin rights): a workflow that names `prod` before it exists auto-creates it **without** a branch restriction. In the repo folder, with `gh` logged in:

```sh
gh api -X PUT "repos/{owner}/{repo}/environments/prod" --input - <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
gh api -X POST "repos/{owner}/{repo}/environments/prod/deployment-branch-policies" -f name=main -f type=branch
gh api "repos/{owner}/{repo}/environments/prod/deployment-branch-policies" --jq '.branch_policies[].name'   # exactly: main
```

The same in the web UI: Settings -> Environments -> `prod` -> Deployment branches and tags -> Selected branches and tags -> add `main`. If repository secrets with these names exist from an earlier setup, delete them (`gh secret delete NAME`).

CD needs a kubeconfig that reaches the API at the tailnet name (`platform-prod`, already a TLS SAN of k3s). Choose **one** of the two options, and upload only that file. Every file below is created readable only by you (`umask 077`).

**Option A (recommended): least privilege.** The ServiceAccount `cd-verify` can only read the Argo CD apps and port-forward to Traefik, which is all verify-deploy does. On the host:

```sh
cd ~ && umask 077
sudo k3s kubectl apply -f - <<'YAML'
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
YAML
rm -f ~/cd-verify.kubeconfig ~/ca.crt
TOKEN=$(sudo k3s kubectl -n kube-system get secret cd-verify-token -o jsonpath='{.data.token}' | base64 -d)
sudo k3s kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > ~/ca.crt
export KUBECONFIG=~/cd-verify.kubeconfig
kubectl config set-cluster prod --server=https://platform-prod:6443 --certificate-authority="$HOME/ca.crt" --embed-certs=true
kubectl config set-credentials cd-verify --token="$TOKEN"
kubectl config set-context prod --cluster=prod --user=cd-verify
kubectl config use-context prod
unset TOKEN KUBECONFIG; rm -f ~/ca.crt
ls -l ~/cd-verify.kubeconfig                                  # must show -rw-------
KUBECONFIG=~/cd-verify.kubeconfig kubectl auth can-i get applications.argoproj.io -n argocd   # yes
KUBECONFIG=~/cd-verify.kubeconfig kubectl auth can-i get secrets -n apps                      # no
```

The last two lines go over the tailnet name, so they also prove that `platform-prod` resolves (MagicDNS). The file to upload is `~/cd-verify.kubeconfig`.

**Option B: cluster-admin** (simpler, but a leak gives full control of the cluster from the tailnet):

```sh
cd ~ && umask 077
sudo sed 's#https://127.0.0.1:6443#https://platform-prod:6443#' /etc/rancher/k3s/k3s.yaml > ~/admin.kubeconfig
```

The file to upload is `~/admin.kubeconfig`.

Copy the chosen file to your computer **into your Downloads folder, never into the repo folder**. The repo ignores `*.kubeconfig` and `sealing-key*.json`, but a file renamed otherwise inside the checkout could still be committed by a `git add`. Use the browser SSH **Download file** (it saves to Downloads), or `gcloud compute scp tuvi-prod-1:~/cd-verify.kubeconfig ~/Downloads/ --zone asia-southeast1-b`. Then, on your computer, in the repo folder with `gh` logged in, run the commands below. The files are read from `~/Downloads`. Each `gh secret set NAME` without `--body` or `<` asks for the value with hidden input: paste it and press Enter.

```sh
gh variable set CD_APP_ID --env prod --body <App ID>   # the App ID number (not a secret), not the Client ID
gh secret set CD_APP_PRIVATE_KEY --env prod < ~/Downloads/<app>.private-key.pem
gh secret set TS_OAUTH_CLIENT_ID --env prod             # paste the Tailscale OAuth client id
gh secret set TS_OAUTH_SECRET --env prod                # paste the Tailscale OAuth client secret
gh secret set PROD_KUBECONFIG --env prod < ~/Downloads/cd-verify.kubeconfig     # or ~/Downloads/admin.kubeconfig (option B)
gh secret list --env prod && gh variable list --env prod                       # names only, never values
```

Use Option A. Option B puts a cluster-admin credential into CI; keep it only for a short test.

Then delete every copy. Store the App private key in the password manager first. Then delete the downloaded kubeconfig and `.pem` on your computer, and empty the Recycle Bin. On the host: `shred -u ~/cd-verify.kubeconfig ~/admin.kubeconfig 2>/dev/null`. You can recreate either file at any time from the host.

Each feature takes all of its secrets or none. A partial set fails the `cd` gate with `::error::`. CD alone (`CD_APP_ID` + `CD_APP_PRIVATE_KEY`) deploys without verification, and the run then shows `::warning::deployed without verification`.

### 6a. Read-only access from your computer (kubectl, logs, Grafana) without installing Tailscale

The ServiceAccount `ops-reader` (`deploy/k8s/overlays/prod/platform/ops-reader.yaml`, synced by `prod-platform`) can read pods, logs, events and workloads in every namespace and port-forward only in `observability`. It cannot read Secrets or ConfigMaps, exec or change anything. Its kubeconfig never goes to CI.

1. On the host (browser SSH), once per 30 days: `cd ~/platform && git pull -q && sudo scripts/ops-kubeconfig.sh`. Download `/home/<your user>/ops-reader.kubeconfig` (gear menu -> Download file; it lands in Downloads, never the repo folder), then `shred -u ~/ops-reader.kubeconfig`. The token expires after 30 days (`--duration`); rerun to renew.
2. In Tailscale -> Settings -> Keys, generate an auth key: **Ephemeral** on, pre-approved, no tag (the node belongs to you, so the ACL lets it reach `tag:prod`). It disappears from the tailnet when the container stops.
3. On your computer (PowerShell), start Tailscale in a throwaway container (userspace networking; nothing installed on Windows). The key is read hidden and passed through the environment, never on the command line:
   ```powershell
   $s = Read-Host -AsSecureString "TS key"; $env:TS_AUTHKEY = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
   docker run -d --name ts-prod -p 127.0.0.1:3000:3000 -e TS_AUTHKEY -e TS_USERSPACE=true -e TS_HOSTNAME=ops-docker -e TS_SOCKS5_SERVER=127.0.0.1:1055 tailscale/tailscale:v1.104.7
   Remove-Item Env:TS_AUTHKEY
   ```
   The image tag is `TAILSCALE_VERSION` in `infra/bootstrap/versions.env`.
4. kubectl runs in the pinned tools image inside the container's network, through its SOCKS5 proxy (which resolves `platform-prod`):
   ```powershell
   docker run --rm --network container:ts-prod -e HTTPS_PROXY=socks5://127.0.0.1:1055 -e KUBECONFIG=/k -v "$HOME\Downloads\ops-reader.kubeconfig:/k:ro" alpine/k8s:1.33.4 kubectl get pods -A
   ```
   Logs: the same command with `kubectl -n apps logs deploy/reading-tuvi --since=1h`.
5. Grafana: `docker run --rm -d --name grafana-pf --network container:ts-prod -e HTTPS_PROXY=socks5://127.0.0.1:1055 -e KUBECONFIG=/k -v "$HOME\Downloads\ops-reader.kubeconfig:/k:ro" alpine/k8s:1.33.4 kubectl -n observability port-forward --address 0.0.0.0 svc/lgtm 3000:3000`, then open http://localhost:3000 (published on 127.0.0.1 only).
6. Done: `docker rm -f grafana-pf ts-prod`.

## 7. First deploy

`main` has the tags `main` until CD pins them. Publish every image for one commit, and let CD pin all 7:

```sh
gh workflow run ci.yml --ref main -f publish_all=true
gh run watch        # pick the new `ci` run (event workflow_dispatch) from the list
gh run watch        # once that is green: pick the `cd` run it started
```

Expected in the `cd` run:
- `bump`: 7 `BUMP` lines and a push of `deploy: ... to sha-<short>`.
- `verify-deploy`: `OK argo-prod-platform`, `OK argo-prod-tunnel`, `OK argo-prod-apps`, `OK port-forward (kube-system/svc/traefik -> http://127.0.0.1:18080)`, `OK smoke-web`.

Argo CD polls Git every 3 minutes, so verify-deploy usually takes 3–10 minutes. Later merges deploy the same way, but only for the services whose images that merge published.

## 8. Check the live system

Run these on the host, in `~/platform`. `sudo` uses k3s's own admin kubeconfig, so no admin file has to leave the host. `pg-restore.sh` needs yq v4; install it once:

```sh
cd ~/platform && git pull -q
sudo snap install yq                                 # once (mikefarah yq v4)
sudo scripts/public-url                              # the URL; open it, the home page says "Lão Thiên Cơ"
curl -fsS "$(sudo scripts/public-url)/" | grep -c 'Lão Thiên Cơ'   # >= 1: the tunnel (never run in CI) serves the site
```

Alerts reach Telegram. The lgtm init container renders the `telegram` contact point **only at pod start**, and lgtm usually started before `alerting-secrets` existed (§4). So restart it once the go-live PR is synced, and after every Telegram token rotation (`docs/runbooks/alerts.md`):
```sh
sudo kubectl -n observability rollout restart deployment/lgtm
sudo kubectl -n observability rollout status deployment/lgtm --timeout=5m
sudo kubectl -n observability logs deploy/lgtm -c alerting-render     # "rules + telegram contact point + policy rendered"
sudo kubectl -n observability port-forward svc/lgtm 13000:3000 >/dev/null 2>&1 & pf=$!
until curl -fsS -o /dev/null http://127.0.0.1:13000/api/health; do sleep 2; done
curl -fsS -u admin:admin http://127.0.0.1:13000/api/v1/provisioning/contact-points | grep -o '"name":"telegram"'   # prints "name":"telegram"
kill "$pf"
```
Without that contact point every alert goes to Grafana's default email receiver, which has no SMTP: silent. Then:
1. Open Grafana through SSH from your computer:
   ```sh
   gcloud compute ssh tuvi-prod-1 --zone asia-southeast1-b -- -L 3000:localhost:3000 \
     'sudo kubectl -n observability port-forward svc/lgtm 3000:3000'
   ```
   Then open http://localhost:3000.
2. Go to Alerting -> Contact points -> `telegram` -> **Test**. A message must arrive in the chat.

Restore drill. Wait for one 03:00 backup, or run it once now. On the host:
```sh
sudo kubectl -n data create job --from=cronjob/pg-backup pg-backup-now
sudo kubectl -n data wait --for=condition=complete job/pg-backup-now --timeout=15m
sudo scripts/pg-restore.sh --db orders --stamp latest     # restores into orders_restore_check; exit 0 = restorable
```

Healthchecks.io shows `heartbeat` up within 5 minutes and `backup` after the first backup.

### Edge limits (Traefik, `deploy/k8s/overlays/prod/apps/middlewares.yaml`)
Per client IP (`Cf-Connecting-IP`, set by Cloudflare): every page 20 req/s (burst 40); login (`/vi/dang-nhap`, `/api/auth`) and Q&A (`/api/qa`) 20 per minute; every Next.js Server Action POST (header `Next-Action`, IngressRoute `web-actions`: an action runs whatever the URL path, so this also bounds a login action POSTed elsewhere) 60 per minute (burst 30); the SePay webhook 60 per minute with a 64 KiB body cap. Above a limit Traefik answers 429. identity-svc also caps magic-link mail at 20 per client IP (IPv4 address or IPv6 /64, sent by web as `X-Client-Ip`; silent, so one client cannot spend the whole global cap) and at `MAIL_DAILY_CAP` (200) per rolling 24 h over all addresses (429, alert `mail-cap-reached`; many distinct IPs can still reach it until Turnstile is on the login form). Many users behind one carrier NAT share an IP: if real users hit 429 on login, raise `ratelimit-strict` in a PR. The CI job `bootstrap` proves the routing and the limits through k3s's Traefik (`scripts/e2e-edge.sh`).

## 9. Rollback and moving to a new host

### Automatic rollback
Every successful verify-deploy moves the tag **`deploy-verified`** to its bump commit. That tag marks the last verified-good prod state. It never moves backwards, and it moves only when nothing in `deploy/k8s/overlays/` changed after that commit.

When `verify-deploy` fails, the `cd` run always fails and opens an issue with the run URL and the last 100 lines of verify-deploy. What else it does depends on the cause:
- **Deployed but unhealthy** (verify-deploy exit 1), and this run's bump is the newest `deploy:` commit on `main`:
  - It restores the last verified state. As **one** revert commit (`revert: deploy sha-<short> failed verify-deploy`, with a CI-skip marker), it reverts every not-yet-reverted CD bump since `deploy-verified`, this one included. So a bad older bump that overlapped with this one is undone too, and Argo CD syncs the last verified tags.
  - Before the first verified deploy (no tag yet), it reverts only its own bump.
  - The issue is labelled `deploy-failed`.
- **Verified, but not recorded** (verify-deploy passed, the `deploy-verified` tag push failed): issue `deploy-unrecorded`. Prod is healthy, but a later rollback would revert past this deploy. Move the tag by hand: `git fetch origin && git tag -f deploy-verified <bump sha> && git push -f origin deploy-verified` (only the CD App or an admin can), then close the issue.
- **Superseded** (a newer CD bump, a `deploy:` commit by the CD App, is already on `main`; a human `deploy: ...` merge does not count): **no revert by this run**. The newest run owns the outcome. If its verify-deploy fails too, it reverts everything since `deploy-verified`, this bump included. If it passes, prod is healthy. The issue says so. Act by hand only if the newest run reports the cluster unreachable (next bullet) while prod is broken.
- **Cluster unreachable** (exit 4: tailnet, API or port-forward down): **no revert**. The deploy is unverified, not known to be bad. One issue labelled `deploy-unverified` is opened, and later runs comment on it. Check Tailscale (the host node and the `tag:ci` ACL) and `PROD_KUBECONFIG`, then close the issue.

Every reverted commit must be a single-parent `deploy:` commit by the CD App that changes only `deploy/k8s/overlays/`. If anything else sits in that range, the rollback is refused and the issue says `deploy-rollback.sh failed`; then revert by hand in a PR.

Read the issue, fix the cause in a normal PR, and the next merge deploys again.

If the issue says `NOT rolled back: the revert conflicted`, a later commit changed the same image line. Revert by hand:
```sh
git revert <bump sha>     # resolve deploy/k8s/overlays/prod/apps/kustomization.yaml to the last good tags
```
Then open a PR. Never push to `main` yourself.

### Manual rollback to a known good commit
Pin one service back in a PR: set its `newTag` in `deploy/k8s/overlays/prod/apps/kustomization.yaml` to the previous `sha-<40 hex>`. CD never moves a tag backwards on its own. `scripts/cd-bump.sh` keeps a tag that is not an ancestor of the new commit, so your pin stays until a newer commit of that service is published.

### Move to a new host (GCP trial end, or a host failure)
Run every command on a host in `~/platform` (the old host for step 2, the new one from step 3 on). `sudo` uses k3s's admin kubeconfig.
1. **New host:** create an Ubuntu 24.04 host (≥ 8 GB). In Tailscale, remove the old `platform-prod` node first, or give the new one another `TS_HOSTNAME` and update `PROD_KUBECONFIG`.
2. **Final backup on the old host, if it still runs:**
   ```sh
   sudo kubectl -n data create job --from=cronjob/pg-backup pg-backup-final
   sudo kubectl -n data wait --for=condition=complete job/pg-backup-final --timeout=15m
   ```
   Without R2 (`ops-secrets` not set up), dump each database directly into a private folder:
   ```sh
   umask 077; mkdir -p ~/dumps
   for db in orders notify reading_tuvi identity payment; do
     sudo kubectl -n data exec postgres-0 -- pg_dump -U postgres -Fc --no-owner --no-acl "$db" > ~/dumps/"$db.dump"
   done
   ```
   Copy `~/dumps` to the new host (`gcloud compute scp --recurse`, or `scp` over the tailnet). Keep the dumps out of Git and off shared drives, because they hold user data. Shred them once step 4 is done.
3. **Bootstrap with all the old sealing keys.** Download the latest `sealing-key.json` from the password manager into your Downloads folder. Copy it to the new host's home directory with `gcloud compute scp ~/Downloads/sealing-key.json <new-vm>:~/ --zone <zone>` (or the browser SSH **Upload file**). Then, on the new host:
   ```sh
   cd ~ && chmod 600 ~/sealing-key.json
   python3 -c 'import json; print(len(json.load(open("sealing-key.json"))["items"]), "keys")'   # every key you backed up
   cd ~/platform
   read -rs TS_AUTHKEY && export TS_AUTHKEY
   sudo --preserve-env=TS_AUTHKEY SEALED_SECRETS_KEYS_FILE="$HOME/sealing-key.json" infra/bootstrap/bootstrap.sh
   unset TS_AUTHKEY; sudo infra/bootstrap/verify.sh
   shred -u ~/sealing-key.json
   ```
   Bootstrap restores **every** key in the file, under its original name, before the controller starts. Each SealedSecret in Git decrypts with the key it was sealed with: renewal never re-encrypts, so all keys are needed. The CI job `bootstrap` proves this on every run (`OK old-key-unseals`: a seal made with an older restored key still decrypts). If the newest key is older than 30 days, the controller adds a new one at start. Then fetch the cert again (step 4), commit `sealing-cert.crt` in a PR, and back up the keys again (step 3). Then delete the downloaded file on your computer and empty the Recycle Bin.
4. **Restore the databases** once `prod-platform` is Healthy and before users arrive. From R2:
   ```sh
   for db in orders notify reading_tuvi identity payment; do
     sudo scripts/pg-restore.sh --db "$db" --stamp latest --target "$db" --overwrite
   done
   ```
   From local dumps:
   ```sh
   for db in orders notify reading_tuvi identity payment; do
     sudo kubectl -n data exec -i postgres-0 -- pg_restore -U postgres --clean --if-exists --no-owner -d "$db" < ~/dumps/"$db.dump"
   done
   sudo kubectl -n apps rollout restart deploy          # services reconnect to the restored data
   shred -u ~/dumps/*.dump && rmdir ~/dumps
   ```
5. **Update and re-run the checks.** A new host has a new k3s CA, so replace `PROD_KUBECONFIG` in any case (step 6). Then run `gh workflow run ci.yml --ref main -f publish_all=true` (step 7), and step 8.
6. **Shut down the old host.** Named tunnel: the new connector takes over the same hostname. Quick tunnel: the URL changes, see `scripts/public-url`.

## Known gaps before real users

These do not block deploying, verify-deploy or rollback. They block the real flows:
- **Quick tunnel = public preview only: no login, no payments.** On `*.trycloudflare.com` the browser's Origin is the random tunnel host while `WEB_ORIGIN` is `APP_DOMAIN`, so web refuses every login, Q&A and purchase request (same-origin check). Magic links would point at `APP_DOMAIN`, `MAIL_FROM` is unverifiable, and the URL changes on every cloudflared restart (SePay would call a dead host). Real users need the domain + named tunnel of §5.
- **No real users before backups:** `ops-secrets` sealed with `BACKUP_REQUIRED="1"` (§5) and one restore drill (§8). Until then `backup-skipped` fires daily.
- **SMTP relay:** set `MAIL_HOST`/`MAIL_PORT` and seal `MAIL_USERNAME`/`MAIL_PASSWORD` (§4, §5); prod sends only with AUTH over STARTTLS. Verify the sender domain (SPF, DKIM) first.
- **SePay webhook:** routed as exactly `POST https://<APP_DOMAIN>/webhooks/sepay` (§5), authenticated only by payment-svc's API key. Needs the domain.
- **Turnstile (optional):** no bot check on the login form yet; edge rate limits and the daily mail cap bound the abuse.
- **Google login:** follow `docs/runbooks/web.md` §2.3 (`OIDC_GOOGLE_CLIENT_ID`, `OIDC_REDIRECT_URIS`, `GOOGLE_LOGIN=1`).
