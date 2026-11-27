# shellcheck shell=bash
# Host network facts shared by lib/firewall.sh and verify.sh (sourced). The SSH lockout guard keys on them: public
# SSH stays open, on every port sshd really listens on, until Tailscale is logged in and connected.

FIREWALL_POD_CIDR=10.42.0.0/16
FIREWALL_SERVICE_CIDR=10.43.0.0/16

# tailscale_connected: tailscaled is logged in and online, i.e. BackendState "Running", Self.Online true and at least
# one Tailscale IP. The tailscale0 link state proves nothing: tailscaled brings it up while logged out or with an
# expired node key too. Anything unreadable (no tailscale, no python3, bad JSON) counts as not connected (fail safe:
# public SSH is kept).
tailscale_connected() {
  local s
  command -v tailscale >/dev/null 2>&1 || return 1
  command -v python3 >/dev/null 2>&1 || return 1
  s=$(tailscale status --json 2>/dev/null) || return 1
  python3 -c '
import json, sys
try:
    s = json.loads(sys.stdin.read())
except ValueError:
    sys.exit(1)
me = s.get("Self") or {}
ips = s.get("TailscaleIPs") or me.get("TailscaleIPs") or []
sys.exit(0 if s.get("BackendState") == "Running" and me.get("Online") is True and len(ips) > 0 else 1)
' <<<"$s"
}

# public_ssh_closing: true only when the operator opted in (BOOTSTRAP_CLOSE_PUBLIC_SSH=1, after checking that SSH over
# the tailnet works) AND Tailscale is connected. Connected alone never closes public SSH: on a cloud VM that would also
# cut the provider's browser SSH/IAP, and nothing proves the operator's own device can reach the node over the tailnet.
public_ssh_closing() {
  [ "${BOOTSTRAP_CLOSE_PUBLIC_SSH:-0}" = 1 ] && tailscale_connected
}

# ssh_ports: every TCP port sshd listens on, one per line, sorted. Union of `sshd -T` (sshd_config), `ss -ltnpH`
# (listening sockets owned by sshd) and ssh.socket's ListenStream (Ubuntu 24.04 socket activation, where the socket
# belongs to systemd, not sshd). Falls back to 22 when none of them answers.
ssh_ports() {
  local out ports=() line tok p
  if out=$(sshd -T 2>/dev/null); then
    while read -r k v _; do [ "$k" = port ] && ports+=("$v"); done <<<"$out"
  fi
  if out=$(ss -ltnpH 2>/dev/null); then
    while IFS= read -r line; do
      [[ "$line" == *'"sshd"'* ]] || continue
      read -r _ _ _ tok _ <<<"$line"
      ports+=("${tok##*:}")
    done <<<"$out"
  fi
  if out=$(systemctl show -p Listen --value ssh.socket 2>/dev/null); then
    for tok in $out; do
      p="${tok##*:}"
      [[ "$p" =~ ^[0-9]+$ ]] && ports+=("$p")
    done
  fi
  local valid=()
  for p in "${ports[@]+"${ports[@]}"}"; do
    [[ "$p" =~ ^[0-9]+$ ]] && [ "$p" -ge 1 ] && [ "$p" -le 65535 ] && valid+=("$p")
  done
  [ "${#valid[@]}" -gt 0 ] || valid=(22)
  printf '%s\n' "${valid[@]}" | sort -un
}

# managed_rule_regex <port...>: the `ufw show added` lines bootstrap owns (lo, tailscale0, pod/service CIDRs, the
# SSH guard ports). Any other allow rule is not ours and could open 6443 or more to the world.
managed_rule_regex() {
  local ports
  ports=$(IFS='|'; echo "$*")
  echo "^ufw allow (in on (lo|tailscale0)|from ${FIREWALL_POD_CIDR//./\\.}|from ${FIREWALL_SERVICE_CIDR//./\\.}|(${ports})/tcp)\$"
}
