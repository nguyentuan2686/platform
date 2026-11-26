# shellcheck shell=bash
# Step firewall: ufw denies incoming except lo, tailscale0 and the k3s pod/service CIDRs (spec §11). SSH lockout guard:
# 22/tcp from anywhere stays allowed while tailscale0 is not up, and is removed once it is.

FIREWALL_POD_CIDR=10.42.0.0/16
FIREWALL_SERVICE_CIDR=10.43.0.0/16

firewall_state() { ufw status verbose 2>&1; ufw show added 2>&1; }

step_firewall() {
  local before after
  if [ "$BOOTSTRAP_FIREWALL" != 1 ]; then status_skip firewall "BOOTSTRAP_FIREWALL=$BOOTSTRAP_FIREWALL"; return 0; fi
  if ! command -v ufw >/dev/null 2>&1; then
    { apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q ufw; } >&2 || die "apt-get install ufw failed"
  fi
  before="$(firewall_state)"

  # Allow rules first, enable last: an SSH session that runs this is never cut off midway.
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow in on lo >/dev/null
  ufw allow in on tailscale0 >/dev/null
  ufw allow from "$FIREWALL_POD_CIDR" >/dev/null
  ufw allow from "$FIREWALL_SERVICE_CIDR" >/dev/null
  if tailscale_up; then
    if ufw show added | grep -qx 'ufw allow 22/tcp'; then ufw delete allow 22/tcp >/dev/null || die "ufw delete allow 22/tcp failed"; fi
  else
    ufw allow 22/tcp >/dev/null
  fi
  ufw --force enable >/dev/null || die "ufw enable failed"

  after="$(firewall_state)"
  if [ "$before" != "$after" ]; then status_changed firewall "ufw rules"; else status_ok firewall; fi
  if ! tailscale_up; then echo "WARN firewall: public SSH kept until Tailscale is up"; fi
}
