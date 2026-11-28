# infra/bootstrap

For the full go-live order (what to prepare, sealing the secrets, GitHub secrets, first deploy, rollback and moving to a new host), follow [`docs/runbooks/go-live.md`](../../docs/runbooks/go-live.md). This README covers the bootstrap script itself.

Idempotent setup of the single prod host: Ubuntu 24.04, amd64 or arm64. It assumes a fresh host. A k3s that bootstrap did not set up is refused unless you pass `--adopt`. Run it as root from a checkout of this repo:

```sh
read -rs TS_AUTHKEY && export TS_AUTHKEY             # never on the command line (shell history, ps)
sudo --preserve-env=TS_AUTHKEY infra/bootstrap/bootstrap.sh   # re-runnable; a second run prints `bootstrap: 0 changed`
sudo infra/bootstrap/verify.sh                       # OK|FAIL per check
```

Use a single-use, pre-approved Tailscale auth key. In the Tailscale admin console, **disable key expiry for this node**. Public SSH is never closed automatically. To close it, see [Closing public SSH](#closing-public-ssh).

Steps, in run order. All k3s refusals (foreign install, newer version) are checked before anything is changed, so a refused host is left exactly as it was.

| Step | What it does |
|---|---|
| `firewall` | Runs before k3s is installed, so 6443/10250 are never reachable from outside, not even during the first run. ufw denies incoming traffic. It allows `lo`, `tailscale0`, the pod CIDR `10.42.0.0/16` and the service CIDR `10.43.0.0/16`. Every port sshd listens on stays open to the world. The ports come from `sshd -T`, sshd's sockets and `ssh.socket`. A `::notice::firewall: ...` line says whether public SSH is open and why. ufw rules that bootstrap does not manage are kept and reported. `BOOTSTRAP_FIREWALL=0` skips this step. |
| `k3s` | Installs the pinned k3s binary and `install.sh` (both sha256-verified). Writes `/etc/rancher/k3s/config.yaml` with a marker first line, `disable: [servicelb]`, kubeconfig `0600`, `tls-san: [$TS_HOSTNAME]` and NodePorts on loopback only. Copies `traefik-config.yaml`, which makes Traefik a ClusterIP Service. It refuses a k3s newer than the pin (never downgrades). Without `--adopt` / `BOOTSTRAP_ADOPT=1`, it also refuses an installed k3s or a `config.yaml` it did not write. With `--adopt`, the old `config.yaml` is saved as `config.yaml.pre-bootstrap.<time>`. Check its `cluster-cidr`/`service-cidr`: the firewall assumes the defaults. |
| `tailscale` | Runs only when `TS_AUTHKEY` is set. Installs the pinned static tarball, unless Tailscale is already installed some other way (dpkg package or another binary): such an install is used as it is. Runs `tailscaled` as a systemd unit and `tailscale up --ssh=false --timeout=120s`, then waits until Tailscale is connected: BackendState `Running`, online, with a Tailscale IP. The tailscale0 link state alone does not count. |
| `sealed-secrets` | Restores every key of the JSON backup in `SEALED_SECRETS_KEYS_FILE`, and/or the one key in `SEALED_SECRETS_KEY_DIR` (`tls.crt` + `tls.key`), if set. Then applies the pinned `controller.yaml`. |
| `argocd` | Applies the pinned `install.yaml` server-side in namespace `argocd`. Adds `argocd-cm-patch.yaml`: the Application health check, so that sync waves between the child apps wait for health, and an Ingress health check (Healthy when it has rules: Traefik is ClusterIP, so Argo's built-in check would stay Progressing forever). The CI-only branch of the check (for apps that CI mode leaves unsynced) is rendered only with `GITOPS_CI_MODE=1`. |
| `gitops-root` | Applies `deploy/argocd/root.yaml` with `GITOPS_REVISION`/`GITOPS_REPO_URL`. With a non-`main` revision or `GITOPS_CI_MODE=1`, every child app follows that revision. CI mode also leaves `prod-tunnel` and `prod-apps` unsynced. |

Each step prints one status line on stdout: `CHANGED <component>: <what>`, `OK <component>` or `SKIP <component>: <reason>`. Every Kubernetes object goes through `kubectl diff --server-side` first and is applied only when the diff reports a change. A diff error stops the run.

Pins (version + sha256 per arch) live in `versions.env`. The comment on each pin names its checksum source. CI runs this script twice on x86 and ARM runners in `.github/workflows/_bootstrap.yml`. Never run it on the dev machine.

## Host migration (spec §12)

1. On the old host, export **all** sealing keys. The controller renews its key every 30 days, and a SealedSecret stays bound to the key it was sealed with: renewal never re-encrypts it. So every key is needed.
   ```sh
   cd ~ && umask 077
   sudo k3s kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o json > ~/sealing-key.json
   ```
   Store the file outside Git (password manager, `docs/runbooks/go-live.md` step 3). Re-export it after each renewal.
2. On the new host, run:
   ```sh
   sudo SEALED_SECRETS_KEYS_FILE="$HOME/sealing-key.json" infra/bootstrap/bootstrap.sh
   ```
   Every key in the file is restored under its original name, so the SealedSecrets already in Git decrypt, whichever key sealed them. `SEALED_SECRETS_KEY_DIR` (a single `tls.crt` + `tls.key`) still works, and CI uses both together.

The controller renews its key every 30 days. On restore, it orders keys by certificate NotBefore, and the newest one seals new secrets. If that newest key is older than 30 days, the controller creates a new key at start. The old keys still decrypt, but new seals should use the new cert. Run `kubeseal --fetch-cert`, then commit the result to `deploy/k8s/overlays/prod/sealed/sealing-cert.crt`.

CI tests this restore path with two throwaway keys that it generates in each run: an older one in a keys file, the active one in a key dir. `OK old-key-unseals` proves that a seal made with the older key still decrypts. No private key is committed, not even a test key.

## Closing public SSH

Public SSH is removed only when both conditions hold:
- `BOOTSTRAP_CLOSE_PUBLIC_SSH=1` is set;
- Tailscale is connected.

Being connected alone never closes it. On a cloud VM, closing it also cuts the provider's browser SSH/IAP, which comes in on the same port from outside.

1. Run the bootstrap with `TS_AUTHKEY`. The notice then reads `public SSH kept on 22/tcp. Tailscale is connected: ...`.
2. From your own device, on the same tailnet, prove SSH over the tailnet works. Keep the public session open meanwhile:
   ```sh
   tailscale status                       # the node is listed and online
   ssh <user>@platform-prod                # MagicDNS name (= TS_HOSTNAME), or: ssh <user>@100.x.y.z
   ```
   It must work in a new session, not only in an existing one. Also check that your tailnet ACLs allow your device to reach the node on port 22.
3. Only then, close public SSH. Run this from the tailnet session; `TS_AUTHKEY` is no longer needed:
   ```sh
   sudo BOOTSTRAP_CLOSE_PUBLIC_SSH=1 infra/bootstrap/bootstrap.sh
   sudo BOOTSTRAP_CLOSE_PUBLIC_SSH=1 infra/bootstrap/verify.sh    # ssh-kept-without-tailscale: public SSH closed
   ```
   Pass the same flag on every later run. A run without it opens public SSH again, which is the fail-safe direction.

### Recovery on GCP (locked out of SSH)

Startup scripts run as root at every boot. To restore public SSH:
```sh
gcloud compute instances add-metadata <vm> --zone <zone> \
  --metadata startup-script='#! /bin/bash
ufw allow 22/tcp'
gcloud compute instances reset <vm> --zone <zone>     # reboot, so the startup script runs
```
After you are back in, remove the startup script:
```sh
gcloud compute instances remove-metadata <vm> --zone <zone> --keys startup-script
```
Then re-run the bootstrap without `BOOTSTRAP_CLOSE_PUBLIC_SSH`.

The serial console is the other path:
```sh
gcloud compute instances add-metadata <vm> --metadata serial-port-enable=TRUE
gcloud compute connect-to-serial-port <vm>
```
It needs a local password login, and Ubuntu cloud images have none by default. Set one beforehand (`sudo passwd <user>`) if you want this path.

### Cloud firewall (VPC) rules

Do not add a permissive VPC firewall rule ("allow all" ingress, or 6443/10250 from 0.0.0.0/0) for this VM. ufw is enabled before k3s is installed, so the host never exposes 6443/10250. A VPC rule is still a second line of defence, and the GCP defaults (22, ICMP, internal) are all this host needs.
