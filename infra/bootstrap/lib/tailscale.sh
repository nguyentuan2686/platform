# shellcheck shell=bash
# Step tailscale (only with TS_AUTHKEY): pinned static tarball, tailscaled as a systemd unit, `tailscale up`
# (Tailscale SSH off: plain sshd over the tailnet), then the firewall step again so public 22/tcp is dropped.

tailscale_running() {
  local s; s=$(tailscale status --json 2>/dev/null) && grep -q '"BackendState": *"Running"' <<<"$s"
}

step_tailscale() {
  local dir="$CACHE_DIR/tailscale-$TAILSCALE_VERSION" tgz src what=() keyfile
  if [ -z "${TS_AUTHKEY:-}" ]; then status_skip tailscale "TS_AUTHKEY not set"; return 0; fi
  tgz="$dir/tailscale_${TAILSCALE_VERSION}_${ARCH}.tgz"
  fetch_verified "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${ARCH}.tgz" "$(pin_sha TAILSCALE)" "$tgz"

  local v; v="$(/usr/bin/tailscale version 2>/dev/null || true)"
  if [[ "${v%%$'\n'*}" != "$TAILSCALE_VERSION" ]]; then
    rm -rf "$dir/x" && mkdir -p "$dir/x"
    tar -xzf "$tgz" -C "$dir/x" --strip-components=1
    src="$dir/x"
    install -m 0755 "$src/tailscaled" /usr/sbin/tailscaled
    install -m 0755 "$src/tailscale" /usr/bin/tailscale
    install -m 0644 "$src/systemd/tailscaled.service" /etc/systemd/system/tailscaled.service
    [ -f /etc/default/tailscaled ] || install -m 0644 "$src/systemd/tailscaled.defaults" /etc/default/tailscaled
    systemctl daemon-reload
    systemctl enable tailscaled >&2
    systemctl restart tailscaled
    what+=("tailscale $TAILSCALE_VERSION installed")
  elif ! systemctl is-active --quiet tailscaled; then
    systemctl start tailscaled; what+=("tailscaled started")
  fi

  if ! tailscale_running; then
    # The auth key is read from a root-only file, never passed on the command line.
    keyfile="$(mktemp)"
    chmod 600 "$keyfile"
    printf '%s' "$TS_AUTHKEY" >"$keyfile"
    tailscale up --auth-key="file:$keyfile" --hostname="$TS_HOSTNAME" --ssh=false >&2 || { rm -f "$keyfile"; die "tailscale up failed"; }
    rm -f "$keyfile"
    what+=("joined the tailnet as $TS_HOSTNAME")
  fi
  wait_until 60 "interface tailscale0 to be up" tailscale_up

  if [ "${#what[@]}" -gt 0 ]; then status_changed tailscale "$(IFS=,; echo "${what[*]}")"; else status_ok tailscale; fi
  step_firewall
}
