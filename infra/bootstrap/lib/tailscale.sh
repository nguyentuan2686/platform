# shellcheck shell=bash
# Step tailscale (only with TS_AUTHKEY): pinned static tarball (unless Tailscale is already installed some other way),
# tailscaled as a systemd unit, `tailscale up` (Tailscale SSH off: plain sshd over the tailnet). Once tailscaled is
# connected (lib/hostnet.sh tailscale_connected), the firewall is converged again, which closes public SSH.

# Written when bootstrap installed the tarball, so a later run knows /usr/bin/tailscale is its own.
TAILSCALE_MARKER="${TAILSCALE_MARKER:-/var/lib/platform-bootstrap/tailscale-tarball}"

# tailscale_install_mode: "package" (dpkg owns it), "foreign" (a tailscale binary bootstrap did not install, e.g.
# /usr/local/bin or a tarball without our marker) or "ours" (absent, or our own tarball install). Package and foreign
# installs are used as they are: overwriting them would fight the package manager on every upgrade.
tailscale_install_mode() {
  local bin
  if dpkg -s tailscale >/dev/null 2>&1; then echo package; return; fi
  bin=$(command -v tailscale 2>/dev/null) || { echo ours; return; }
  if [ "$bin" = /usr/bin/tailscale ] && [ -f "$TAILSCALE_MARKER" ]; then echo ours; else echo foreign; fi
}

step_tailscale() {
  local dir="$CACHE_DIR/tailscale-$TAILSCALE_VERSION" tgz src what=() keyfile mode v
  if [ -z "${TS_AUTHKEY:-}" ]; then status_skip tailscale "TS_AUTHKEY not set"; return 0; fi
  mode="$(tailscale_install_mode)"

  if [ "$mode" = ours ]; then
    tgz="$dir/tailscale_${TAILSCALE_VERSION}_${ARCH}.tgz"
    fetch_verified "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${ARCH}.tgz" "$(pin_sha TAILSCALE)" "$tgz"
    v="$(/usr/bin/tailscale version 2>/dev/null || true)"
    if [[ "${v%%$'\n'*}" != "$TAILSCALE_VERSION" ]]; then
      rm -rf "$dir/x" && mkdir -p "$dir/x"
      tar -xzf "$tgz" -C "$dir/x" --strip-components=1
      src="$dir/x"
      install -m 0755 "$src/tailscaled" /usr/sbin/tailscaled
      install -m 0755 "$src/tailscale" /usr/bin/tailscale
      install -m 0644 "$src/systemd/tailscaled.service" /etc/systemd/system/tailscaled.service
      [ -f /etc/default/tailscaled ] || install -m 0644 "$src/systemd/tailscaled.defaults" /etc/default/tailscaled
      mkdir -p "$(dirname "$TAILSCALE_MARKER")" && echo "$TAILSCALE_VERSION" >"$TAILSCALE_MARKER"
      systemctl daemon-reload
      systemctl enable tailscaled >&2
      systemctl restart tailscaled
      what+=("tailscale $TAILSCALE_VERSION installed")
    fi
  else
    note "tailscale: using the existing $mode install ($(command -v tailscale || echo dpkg)), not the pinned tarball"
  fi
  if ! systemctl is-active --quiet tailscaled; then
    systemctl start tailscaled || die "systemctl start tailscaled failed"; what+=("tailscaled started")
  fi

  if ! tailscale_connected; then
    # The auth key is read from a root-only file, never passed on the command line.
    keyfile="$(mktemp)"
    chmod 600 "$keyfile"
    printf '%s' "$TS_AUTHKEY" >"$keyfile"
    tailscale up --auth-key="file:$keyfile" --hostname="$TS_HOSTNAME" --ssh=false --timeout=120s >&2 \
      || { rm -f "$keyfile"; die "tailscale up failed (public SSH stays open)"; }
    rm -f "$keyfile"
    what+=("joined the tailnet as $TS_HOSTNAME")
  fi
  wait_until 120 "tailscaled to be logged in and connected" tailscale_connected

  # Converge the firewall now that Tailscale is connected: this closes public SSH. Reported in this line, so the
  # firewall component keeps exactly one status line per run.
  if [ "$BOOTSTRAP_FIREWALL" = 1 ] && firewall_apply; then what+=("public SSH closed"); fi
  if [ "${#what[@]}" -gt 0 ]; then status_changed tailscale "$(IFS=,; echo "${what[*]}")"; else status_ok tailscale; fi
}
