#!/usr/bin/env bash
# Write a read-only kubeconfig for the ServiceAccount kube-system/ops-reader (deploy/k8s/overlays/prod/platform/
# ops-reader.yaml) that reaches the API over the tailnet. Run on the prod host: `sudo scripts/ops-kubeconfig.sh`.
# Usage: scripts/ops-kubeconfig.sh [--out FILE] [--duration 720h] [--server https://platform-prod:6443]
#   --out default ~<sudo user>/ops-reader.kubeconfig, mode 0600, owned by the sudo user. The token is a TokenRequest
#   token that expires after --duration (no long-lived token Secret); rerun to renew. The token is never printed.
#   KUBECTL (default `k3s kubectl`) is the admin kubectl on the host.
set -euo pipefail

user="${SUDO_USER:-$(id -un)}"
home="$(getent passwd "$user" 2>/dev/null | cut -d: -f6 || true)"
out="${home:-$HOME}/ops-reader.kubeconfig" duration=720h server="https://platform-prod:6443"
read -r -a kubectl <<<"${KUBECTL:-k3s kubectl}"

die() { echo "ops-kubeconfig: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="${2:?--out needs a file}"; shift 2 ;;
    --duration) duration="${2:?--duration needs a value}"; shift 2 ;;
    --server) server="${2:?--server needs a URL}"; shift 2 ;;
    *) die "unknown argument $1 (see the usage at the top of $0)" ;;
  esac
done
[[ "$duration" =~ ^[0-9]+[hm]$ ]] || die "--duration must look like 720h or 30m: $duration"
[[ "$server" =~ ^https://[A-Za-z0-9.-]+:[0-9]+$ ]] || die "--server must be https://<host>:<port>: $server"

"${kubectl[@]}" -n kube-system get serviceaccount ops-reader >/dev/null 2>&1 \
  || die "ServiceAccount kube-system/ops-reader not found (is prod-platform synced?)"
token="$("${kubectl[@]}" -n kube-system create token ops-reader --duration="$duration")" || die "create token failed"
[ -n "$token" ] || die "empty token"
ca="$("${kubectl[@]}" config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')" \
  || die "cannot read the cluster CA"
[ -n "$ca" ] || die "empty cluster CA"

umask 077
tmp="$(mktemp "$(dirname "$out")/.ops-kubeconfig.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cat >"$tmp" <<YAML
apiVersion: v1
kind: Config
clusters:
  - name: prod
    cluster: {server: "$server", certificate-authority-data: "$ca"}
users:
  - name: ops-reader
    user: {token: "$token"}
contexts:
  - name: prod-ops-reader
    context: {cluster: prod, user: ops-reader}
current-context: prod-ops-reader
YAML
chmod 600 "$tmp"
[ "$(id -u)" -ne 0 ] || [ "$user" = root ] || chown "$user:" "$tmp"
mv -f "$tmp" "$out"
trap - EXIT
echo "ops-kubeconfig: wrote $out (ops-reader, read-only, expires in $duration, server $server)"
