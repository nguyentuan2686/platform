#!/usr/bin/env bash
# Tests for the host-safety logic of infra/bootstrap/lib (no root, no host changes): fake sshd/ss/systemctl/tailscale/
# dpkg on PATH. Prints "ok <case>" per case; exits non-zero on the first failure. The tailscale_connected cases need
# python3 (as the bootstrap does); without it they print "skip".
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

lib() { # run "$@" with the bootstrap libs sourced and $tmp/bin first on PATH
  (
    # shellcheck disable=SC2034 # read by the sourced libs
    BOOTSTRAP_DIR="$root/infra/bootstrap" GITOPS_CI_MODE="${CI_MODE:-0}" TS_HOSTNAME=platform-prod
    # shellcheck source=../../infra/bootstrap/versions.env
    . "$root/infra/bootstrap/versions.env"
    # shellcheck source=../../infra/bootstrap/lib/common.sh
    . "$root/infra/bootstrap/lib/common.sh"
    # shellcheck source=../../infra/bootstrap/lib/hostnet.sh
    . "$root/infra/bootstrap/lib/hostnet.sh"
    # shellcheck source=../../infra/bootstrap/lib/k3s.sh
    . "$root/infra/bootstrap/lib/k3s.sh"
    # shellcheck source=../../infra/bootstrap/lib/tailscale.sh
    . "$root/infra/bootstrap/lib/tailscale.sh"
    # shellcheck source=../../infra/bootstrap/lib/firewall.sh
    . "$root/infra/bootstrap/lib/firewall.sh"
    # shellcheck source=../../infra/bootstrap/lib/argocd.sh
    . "$root/infra/bootstrap/lib/argocd.sh"
    PATH="$tmp/bin:$PATH"
    "$@"
  )
}

fake() { # fake <name> <script body>
  mkdir -p "$tmp/bin"
  printf '#!/usr/bin/env bash\n%s\n' "$2" >"$tmp/bin/$1"
  chmod +x "$tmp/bin/$1"
}
reset_fakes() { rm -rf "${tmp:?}/bin"; mkdir -p "$tmp/bin"; }

ssh_ports_union_of_sshd_ss_and_socket() {
  reset_fakes
  fake sshd 'echo "port 2222"; echo "addressfamily any"'
  fake ss 'printf "%s\n" "LISTEN 0 128 0.0.0.0:22 0.0.0.0:* users:((\"sshd\",pid=1,fd=3))" "LISTEN 0 128 [::]:2200 [::]:* users:((\"sshd\",pid=1,fd=4))" "LISTEN 0 4096 *:6443 *:* users:((\"k3s-server\",pid=9,fd=7))"'
  fake systemctl 'echo "0.0.0.0:2022 [::]:2022"'
  got=$(lib ssh_ports | paste -sd, -)
  [ "$got" = 22,2022,2200,2222 ] || fail "${FUNCNAME[0]}" "got [$got]"
  echo "ok ${FUNCNAME[0]}"
}

ssh_ports_falls_back_to_22() {
  reset_fakes
  fake sshd 'exit 1'; fake ss 'exit 1'; fake systemctl 'exit 1'
  got=$(lib ssh_ports | paste -sd, -)
  [ "$got" = 22 ] || fail "${FUNCNAME[0]}" "got [$got]"
  echo "ok ${FUNCNAME[0]}"
}

managed_rules_cover_ssh_ports_only() {
  local re
  re=$(lib managed_rule_regex 22 2222)
  for r in 'ufw allow in on lo' 'ufw allow in on tailscale0' 'ufw allow from 10.42.0.0/16' 'ufw allow from 10.43.0.0/16' 'ufw allow 22/tcp' 'ufw allow 2222/tcp'; do
    grep -Eq "$re" <<<"$r" || fail "${FUNCNAME[0]}" "managed rule not matched: $r"
  done
  for r in 'ufw allow 6443/tcp' 'ufw allow OpenSSH' 'ufw allow 80/tcp' 'ufw allow from 10.42.0.0/8' 'ufw allow 2223/tcp'; do
    if grep -Eq "$re" <<<"$r"; then fail "${FUNCNAME[0]}" "foreign rule matched: $r"; fi
  done
  echo "ok ${FUNCNAME[0]}"
}

tailscale_connected_needs_running_online_and_ip() {
  if ! command -v python3 >/dev/null 2>&1; then echo "skip ${FUNCNAME[0]} (no python3)"; return; fi
  reset_fakes
  ts() { fake tailscale "echo '$1'"; }
  ts '{"BackendState":"Running","TailscaleIPs":["100.64.0.1"],"Self":{"Online":true}}'
  lib tailscale_connected || fail "${FUNCNAME[0]}" "connected node not recognised"
  ts '{"BackendState":"NeedsLogin","TailscaleIPs":[],"Self":{"Online":false}}'
  if lib tailscale_connected; then fail "${FUNCNAME[0]}" "logged-out node counted as connected"; fi
  ts '{"BackendState":"Running","TailscaleIPs":["100.64.0.1"],"Self":{"Online":false}}'
  if lib tailscale_connected; then fail "${FUNCNAME[0]}" "offline node (expired key) counted as connected"; fi
  ts '{"BackendState":"Running","TailscaleIPs":[],"Self":{"Online":true}}'
  if lib tailscale_connected; then fail "${FUNCNAME[0]}" "node without a Tailscale IP counted as connected"; fi
  ts 'not json'
  if lib tailscale_connected; then fail "${FUNCNAME[0]}" "unparsable status counted as connected"; fi
  echo "ok ${FUNCNAME[0]}"
}

k3s_existing_host_decisions() {
  local pin d
  pin=$(lib eval 'echo "$K3S_VERSION"')
  check() { # installed config adopt expected-prefix
    d=$(lib k3s_existing_decision "$1" "$2" "$3")
    [[ "$d" == "$4"* ]] || fail "${FUNCNAME[1]}" "installed=[$1] config=$2 adopt=$3: got [$d], want [$4...]"
  }
  check "" absent 0 install
  check "" ours 0 install
  check "" foreign 0 refuse:
  check "" foreign 1 install
  check "$pin" ours 0 keep
  check "$pin" absent 0 refuse:
  check "$pin" absent 1 keep
  check "$pin" foreign 0 refuse:
  check v1.30.0+k3s1 ours 0 upgrade
  check v1.30.0+k3s1 foreign 1 upgrade
  check v9.99.0+k3s1 ours 0 "refuse:k3s v9.99.0+k3s1 is newer"
  check v9.99.0+k3s1 ours 1 "refuse:k3s v9.99.0+k3s1 is newer"
  echo "ok ${FUNCNAME[0]}"
}

k3s_config_marker_is_recognised() {
  lib k3s_config >"$tmp/ours.yaml"
  printf 'disable:\n  - traefik\n' >"$tmp/foreign.yaml"
  [ "$(lib k3s_config_kind "$tmp/ours.yaml")" = ours ] || fail "${FUNCNAME[0]}" "own config not recognised"
  [ "$(lib k3s_config_kind "$tmp/foreign.yaml")" = foreign ] || fail "${FUNCNAME[0]}" "foreign config not detected"
  [ "$(lib k3s_config_kind "$tmp/missing.yaml")" = absent ] || fail "${FUNCNAME[0]}" "missing config not absent"
  grep -qx '  - nodeport-addresses=127.0.0.1/32' "$tmp/ours.yaml" || fail "${FUNCNAME[0]}" "NodePorts not limited to loopback"
  echo "ok ${FUNCNAME[0]}"
}

tailscale_package_install_is_used_as_is() {
  reset_fakes
  fake dpkg 'exit 0'
  [ "$(lib tailscale_install_mode)" = package ] || fail "${FUNCNAME[0]}" "dpkg-owned tailscale not detected"
  fake dpkg 'exit 1'
  fake tailscale 'exit 0' # a tailscale on PATH outside /usr/bin, without our marker
  [ "$(TAILSCALE_MARKER="$tmp/none" lib tailscale_install_mode)" = foreign ] || fail "${FUNCNAME[0]}" "foreign tailscale not detected"
  rm -f "$tmp/bin/tailscale"
  if ! command -v tailscale >/dev/null 2>&1; then
    [ "$(lib tailscale_install_mode)" = ours ] || fail "${FUNCNAME[0]}" "absent tailscale should be installed by bootstrap"
  fi
  echo "ok ${FUNCNAME[0]}"
}

argocd_cm_ci_branch_only_in_ci_mode() {
  CI_MODE=1 lib render_argocd_cm >"$tmp/ci.yaml"
  CI_MODE=0 lib render_argocd_cm >"$tmp/prod.yaml"
  grep -q 'ci-manual-sync' "$tmp/ci.yaml" || fail "${FUNCNAME[0]}" "CI mode lost the manual-sync branch"
  local prod ci # YAML comments name the annotation; only the rendered data counts
  prod=$(grep -v '^[[:space:]]*#' "$tmp/prod.yaml")
  ci=$(grep -v '^[[:space:]]*#' "$tmp/ci.yaml")
  if grep -q 'ci-manual-sync\|ci-mode-' <<<"$prod"; then fail "${FUNCNAME[0]}" "prod argocd-cm carries the CI-only branch"; fi
  grep -q 'hs.status = obj.status.health.status' <<<"$prod" || fail "${FUNCNAME[0]}" "prod lost the health passthrough"
  if grep -q 'ci-mode-' <<<"$ci"; then fail "${FUNCNAME[0]}" "markers left in the CI render"; fi
  echo "ok ${FUNCNAME[0]}"
}

fake_ufw() { # ufw that keeps its user rules in $tmp/ufw.rules
  : >"$tmp/ufw.rules"
  fake ufw 'db="$FAKE_UFW_DB"; touch "$db"
case "$1" in
  status) echo "Status: active"; echo "Default: deny (incoming), allow (outgoing), deny (routed)" ;;
  show) echo "Added user rules (see '"'"'ufw status'"'"' for running firewall):"; cat "$db" ;;
  default | --force) ;;
  allow) r="ufw $*"; grep -qxF "$r" "$db" || echo "$r" >>"$db" ;;
  delete) shift; r="ufw $*"; grep -vxF "$r" "$db" >"$db.t" || true; mv "$db.t" "$db" ;;
  *) echo "fake ufw: unexpected $*" >&2; exit 2 ;;
esac'
}

# fw <connected 0|1> <close flag 0|1>: run firewall_apply + firewall_ssh_notice against the fake ufw.
fw() {
  if [ "$1" = 1 ]; then fake tailscale "echo '{\"BackendState\":\"Running\",\"TailscaleIPs\":[\"100.64.0.1\"],\"Self\":{\"Online\":true}}'"
  else fake tailscale "echo '{\"BackendState\":\"NeedsLogin\",\"TailscaleIPs\":[],\"Self\":{\"Online\":false}}'"; fi
  FAKE_UFW_DB="$tmp/ufw.rules" BOOTSTRAP_CLOSE_PUBLIC_SSH="$2" lib eval 'firewall_apply || true; firewall_ssh_notice' >"$tmp/notice" 2>&1 \
    || fail "${FUNCNAME[1]}" "firewall_apply failed: $(cat "$tmp/notice")"
}

public_ssh_closes_only_on_opt_in_while_connected() {
  if ! command -v python3 >/dev/null 2>&1; then echo "skip ${FUNCNAME[0]} (no python3)"; return; fi
  reset_fakes; fake_ufw
  fake sshd 'echo "port 22"'; fake ss 'exit 1'; fake systemctl 'exit 1'
  fw 0 0
  grep -qx 'ufw allow 22/tcp' "$tmp/ufw.rules" || fail "${FUNCNAME[0]}" "not connected: 22/tcp not allowed"
  grep -q 'public SSH kept on 22/tcp (Tailscale not connected)' "$tmp/notice" || fail "${FUNCNAME[0]}" "notice: $(cat "$tmp/notice")"
  fw 1 0
  grep -qx 'ufw allow 22/tcp' "$tmp/ufw.rules" || fail "${FUNCNAME[0]}" "connected without opt-in closed public SSH"
  grep -q '^::notice::firewall: public SSH kept on 22/tcp. Tailscale is connected: check SSH over the tailnet, then re-run with BOOTSTRAP_CLOSE_PUBLIC_SSH=1' "$tmp/notice" \
    || fail "${FUNCNAME[0]}" "notice: $(cat "$tmp/notice")"
  fw 0 1
  grep -qx 'ufw allow 22/tcp' "$tmp/ufw.rules" || fail "${FUNCNAME[0]}" "opt-in without a connected Tailscale closed public SSH"
  fw 1 1
  if grep -qx 'ufw allow 22/tcp' "$tmp/ufw.rules"; then fail "${FUNCNAME[0]}" "opt-in while connected kept public SSH"; fi
  grep -q '^::notice::firewall: public SSH closed on 22/tcp' "$tmp/notice" || fail "${FUNCNAME[0]}" "notice: $(cat "$tmp/notice")"
  grep -qx 'ufw allow from 10.42.0.0/16' "$tmp/ufw.rules" || fail "${FUNCNAME[0]}" "pod CIDR rule missing"
  echo "ok ${FUNCNAME[0]}"
}

ssh_ports_union_of_sshd_ss_and_socket
ssh_ports_falls_back_to_22
managed_rules_cover_ssh_ports_only
tailscale_connected_needs_running_online_and_ip
k3s_existing_host_decisions
k3s_config_marker_is_recognised
tailscale_package_install_is_used_as_is
argocd_cm_ci_branch_only_in_ci_mode
public_ssh_closes_only_on_opt_in_while_connected
