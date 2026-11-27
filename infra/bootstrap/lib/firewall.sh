# shellcheck shell=bash
# Step firewall: ufw denies incoming except lo, tailscale0 and the k3s pod/service CIDRs (spec §11). It runs before
# k3s is installed, so 6443/10250 are never reachable from outside, not even during the first run. SSH lockout guard:
# every port sshd listens on (lib/hostnet.sh ssh_ports, not just 22) stays open from anywhere. Those public rules are
# removed only on explicit opt-in: BOOTSTRAP_CLOSE_PUBLIC_SSH=1 while Tailscale is connected (public_ssh_closing).

firewall_state() { ufw status verbose 2>&1; ufw show added 2>&1; }

# firewall_apply: converge ufw; returns 0 when something changed, 1 when it was already in place. Prints nothing on
# stdout (step_firewall and step_tailscale report the result in their own status line).
firewall_apply() {
  local before after p added ports
  if ! command -v ufw >/dev/null 2>&1; then
    { apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q ufw; } >&2 || die "apt-get install ufw failed"
  fi
  mapfile -t ports < <(ssh_ports)
  before="$(firewall_state)"

  # Allow rules first, enable last: an SSH session that runs this is never cut off midway.
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow in on lo >/dev/null
  ufw allow in on tailscale0 >/dev/null
  ufw allow from "$FIREWALL_POD_CIDR" >/dev/null
  ufw allow from "$FIREWALL_SERVICE_CIDR" >/dev/null
  if public_ssh_closing; then
    added="$(ufw show added)"
    for p in "${ports[@]}"; do
      if grep -qx "ufw allow $p/tcp" <<<"$added"; then ufw delete allow "$p/tcp" >/dev/null || die "ufw delete allow $p/tcp failed"; fi
    done
  else
    for p in "${ports[@]}"; do ufw allow "$p/tcp" >/dev/null; done
  fi
  ufw --force enable >/dev/null || die "ufw enable failed"

  after="$(firewall_state)"
  [ "$before" != "$after" ]
}

# firewall_ssh_notice: one ::notice:: line saying whether public SSH is open and why (also a GitHub annotation in CI).
firewall_ssh_notice() {
  local list ports
  mapfile -t list < <(ssh_ports)
  ports="$(IFS=,; echo "${list[*]}")/tcp"
  if public_ssh_closing; then
    echo "::notice::firewall: public SSH closed on $ports (BOOTSTRAP_CLOSE_PUBLIC_SSH=1, Tailscale connected): SSH only over the tailnet"
  elif tailscale_connected; then
    echo "::notice::firewall: public SSH kept on $ports. Tailscale is connected: check SSH over the tailnet, then re-run with BOOTSTRAP_CLOSE_PUBLIC_SSH=1 to close it (infra/bootstrap/README.md)"
  else
    echo "::notice::firewall: public SSH kept on $ports (Tailscale not connected)"
  fi
}

step_firewall() {
  local ports foreign
  if [ "$BOOTSTRAP_FIREWALL" != 1 ]; then status_skip firewall "BOOTSTRAP_FIREWALL=$BOOTSTRAP_FIREWALL"; return 0; fi
  if firewall_apply; then status_changed firewall "ufw rules"; else status_ok firewall; fi
  firewall_ssh_notice
  # Rules bootstrap does not own are left alone but reported: e.g. a leftover `allow OpenSSH` keeps SSH public.
  mapfile -t ports < <(ssh_ports)
  foreign=$(grep '^ufw ' <<<"$(ufw show added)" | grep -Ev "$(managed_rule_regex "${ports[@]}")" | paste -sd';' -) || true
  if [ -n "$foreign" ]; then echo "WARN firewall: ufw rules not managed by bootstrap: $foreign"; fi
}
