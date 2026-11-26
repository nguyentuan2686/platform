#!/usr/bin/env bash
# Idempotent host bootstrap for the single prod node (Ubuntu 24.04, amd64 or arm64): k3s, firewall, Tailscale,
# Sealed Secrets, Argo CD and the GitOps root app. Run as root from a checkout of this repo:
#   sudo infra/bootstrap/bootstrap.sh [--adopt]
# A host that already runs a k3s bootstrap did not set up is refused unless --adopt (BOOTSTRAP_ADOPT=1); a k3s newer
# than the pin is always refused (never downgraded). A packaged Tailscale is used as installed.
# Environment (all optional):
#   GITOPS_REPO_URL         Git repo Argo CD syncs (default https://github.com/nguyentuan2686/platform.git)
#   GITOPS_REVISION         branch/tag/sha for the root and child apps (default main)
#   GITOPS_CI_MODE          1 = CI: children follow GITOPS_REVISION, prod-tunnel/prod-apps are not auto-synced (default 0)
#   TS_AUTHKEY              Tailscale auth key; unset = Tailscale step skipped and public SSH kept
#   TS_HOSTNAME             Tailscale hostname, also a k3s tls-san (default platform-prod)
#   SEALED_SECRETS_KEY_DIR  dir with tls.crt + tls.key of an existing sealing key to restore (host migration)
#   BOOTSTRAP_FIREWALL      0 = leave ufw alone (default 1)
#   BOOTSTRAP_ADOPT         1 = same as --adopt (default 0)
# Every step prints exactly one of `CHANGED <component>: <what>`, `OK <component>` or `SKIP <component>: <reason>` on
# stdout (details go to stderr); the last line is `bootstrap: <n> changed`. A second run on an unchanged host prints
# `bootstrap: 0 changed`. Pins: versions.env. Checks: verify.sh. Details: README.md.
set -euo pipefail

BOOTSTRAP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC2034 # used by the sourced lib/argocd.sh
REPO_ROOT="$(cd "$BOOTSTRAP_DIR/../.." && pwd)"

# shellcheck source=versions.env
. "$BOOTSTRAP_DIR/versions.env"
# shellcheck source=lib/common.sh
. "$BOOTSTRAP_DIR/lib/common.sh"
# shellcheck source=lib/k3s.sh
. "$BOOTSTRAP_DIR/lib/k3s.sh"
# shellcheck source=lib/hostnet.sh
. "$BOOTSTRAP_DIR/lib/hostnet.sh"
# shellcheck source=lib/firewall.sh
. "$BOOTSTRAP_DIR/lib/firewall.sh"
# shellcheck source=lib/tailscale.sh
. "$BOOTSTRAP_DIR/lib/tailscale.sh"
# shellcheck source=lib/sealed.sh
. "$BOOTSTRAP_DIR/lib/sealed.sh"
# shellcheck source=lib/argocd.sh
. "$BOOTSTRAP_DIR/lib/argocd.sh"

GITOPS_REPO_URL="${GITOPS_REPO_URL:-$DEFAULT_GITOPS_REPO_URL}"
GITOPS_REVISION="${GITOPS_REVISION:-main}"
GITOPS_CI_MODE="${GITOPS_CI_MODE:-0}"
TS_HOSTNAME="${TS_HOSTNAME:-platform-prod}"
BOOTSTRAP_FIREWALL="${BOOTSTRAP_FIREWALL:-1}"
SEALED_SECRETS_KEY_DIR="${SEALED_SECRETS_KEY_DIR:-}"
BOOTSTRAP_ADOPT="${BOOTSTRAP_ADOPT:-0}"
for arg in "$@"; do
  case "$arg" in
    --adopt) BOOTSTRAP_ADOPT=1 ;;
    *) die "unknown argument $arg (see the header of $0)" ;;
  esac
done

case "$GITOPS_CI_MODE" in 0 | 1) ;; *) die "GITOPS_CI_MODE must be 0 or 1" ;; esac
case "$BOOTSTRAP_FIREWALL" in 0 | 1) ;; *) die "BOOTSTRAP_FIREWALL must be 0 or 1" ;; esac
case "$BOOTSTRAP_ADOPT" in 0 | 1) ;; *) die "BOOTSTRAP_ADOPT must be 0 or 1" ;; esac
[[ "$TS_HOSTNAME" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || die "TS_HOSTNAME must be a DNS label: $TS_HOSTNAME"
if [ -n "$SEALED_SECRETS_KEY_DIR" ]; then
  [ -d "$SEALED_SECRETS_KEY_DIR" ] || die "SEALED_SECRETS_KEY_DIR $SEALED_SECRETS_KEY_DIR is not a directory"
  SEALED_SECRETS_KEY_DIR="$(cd "$SEALED_SECRETS_KEY_DIR" && pwd)"
fi

[ "$(id -u)" = 0 ] || die "run as root (sudo)"
# shellcheck disable=SC1091 # host file
os_id="$(. /etc/os-release && echo "${ID:-}:${VERSION_ID:-}")"
[ "$os_id" = "ubuntu:24.04" ] || die "Ubuntu 24.04 required (found $os_id)"
case "$(uname -m)" in
  x86_64) ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
  *) die "unsupported architecture $(uname -m) (x86_64 or aarch64)" ;;
esac
export ARCH

note "bootstrap: arch $ARCH, revision $GITOPS_REVISION, ci mode $GITOPS_CI_MODE"
step_k3s
step_firewall
step_tailscale
step_sealed
step_argocd
step_gitops_root
echo "bootstrap: $CHANGED_COUNT changed"
