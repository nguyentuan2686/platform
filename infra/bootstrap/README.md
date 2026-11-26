# infra/bootstrap

Idempotent setup of the single prod host: Ubuntu 24.04, amd64 or arm64. It assumes a fresh host. A k3s that bootstrap did not set up is refused unless you pass `--adopt`. Run it as root from a checkout of this repo:

```sh
read -rs TS_AUTHKEY && export TS_AUTHKEY             # never on the command line (shell history, ps)
sudo --preserve-env=TS_AUTHKEY infra/bootstrap/bootstrap.sh   # re-runnable; a second run prints `bootstrap: 0 changed`
sudo infra/bootstrap/verify.sh                       # OK|FAIL per check
```

Use a single-use, pre-approved Tailscale auth key. In the Tailscale admin console, **disable key expiry for this node**. An expired node key disconnects the node, and bootstrap then reopens public SSH on the next run.

| Step | What it does |
|---|---|
| `k3s` | Installs the pinned k3s binary and `install.sh` (both sha256-verified). Writes `/etc/rancher/k3s/config.yaml` with a marker first line, `disable: [servicelb]`, kubeconfig `0600`, `tls-san: [$TS_HOSTNAME]` and NodePorts on loopback only. Copies `traefik-config.yaml`, which makes Traefik a ClusterIP Service. It refuses a k3s newer than the pin (never downgrades). Without `--adopt` / `BOOTSTRAP_ADOPT=1`, it also refuses an installed k3s or a `config.yaml` it did not write. With `--adopt`, the old `config.yaml` is saved as `config.yaml.pre-bootstrap.<time>`. Check its `cluster-cidr`/`service-cidr`: the firewall assumes the defaults. |
| `firewall` | ufw denies incoming traffic. It allows `lo`, `tailscale0`, the pod CIDR `10.42.0.0/16` and the service CIDR `10.43.0.0/16`. Every port sshd listens on stays open to the world until Tailscale is logged in and connected (`WARN firewall: ...`), so you cannot lock yourself out. The ports come from `sshd -T`, sshd's sockets and `ssh.socket`. Connected means BackendState `Running`, online, with a Tailscale IP; the tailscale0 link state alone does not count. ufw rules that bootstrap does not manage are kept and reported. `BOOTSTRAP_FIREWALL=0` skips this step. |
| `tailscale` | Runs only when `TS_AUTHKEY` is set. Installs the pinned static tarball, unless Tailscale is already installed some other way (dpkg package or another binary): such an install is used as it is. Runs `tailscaled` as a systemd unit and `tailscale up --ssh=false --timeout=120s`. Once Tailscale is connected, it converges the firewall, which closes public SSH. |
| `sealed-secrets` | Restores the key in `SEALED_SECRETS_KEY_DIR` (`tls.crt` + `tls.key`) if set. Then applies the pinned `controller.yaml`. |
| `argocd` | Applies the pinned `install.yaml` server-side in namespace `argocd`. Adds `argocd-cm-patch.yaml`, the Application health check, so that sync waves between the child apps wait for health. The CI-only branch of the check (for apps that CI mode leaves unsynced) is rendered only with `GITOPS_CI_MODE=1`. |
| `gitops-root` | Applies `deploy/argocd/root.yaml` with `GITOPS_REVISION`/`GITOPS_REPO_URL`. With a non-`main` revision or `GITOPS_CI_MODE=1`, every child app follows that revision. CI mode also leaves `prod-tunnel` and `prod-apps` unsynced. |

Each step prints one status line on stdout: `CHANGED <component>: <what>`, `OK <component>` or `SKIP <component>: <reason>`. Every Kubernetes object goes through `kubectl diff --server-side` first and is applied only when the diff reports a change. A diff error stops the run.

Pins (version + sha256 per arch) live in `versions.env`. The comment on each pin names its checksum source. CI runs this script twice on x86 and ARM runners in `.github/workflows/_bootstrap.yml`. Never run it on the dev machine.

## Host migration (spec §12)

1. On the old host, copy the active sealing key:
   ```sh
   kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key=active -o yaml
   ```
   Store the `tls.crt`/`tls.key` of the newest key outside Git, and shred the copy once the new host runs.
2. On the new host, run:
   ```sh
   sudo SEALED_SECRETS_KEY_DIR=<dir> infra/bootstrap/bootstrap.sh
   ```
   The SealedSecrets already in Git decrypt with the restored key.

The controller rotates its key every 30 days. On restore, it orders keys by certificate NotBefore. If the restored key is older than 30 days, the controller creates a new key at start. The old key still decrypts, but new seals should use the new cert. Run `kubeseal --fetch-cert`, then commit the result to `deploy/k8s/overlays/prod/sealed/sealing-cert.crt`.

CI tests this restore path with a throwaway key that it generates in each run. No private key is committed, not even a test key.
