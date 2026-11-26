# shellcheck shell=bash
# Step k3s: pinned binary + install.sh (both sha256-verified), servicelb disabled, kubeconfig 0600, Traefik ClusterIP.

k3s_config() {
  cat <<EOF
# Written by infra/bootstrap (lib/k3s.sh). No public LoadBalancer: servicelb is off and Traefik is ClusterIP.
disable:
  - servicelb
write-kubeconfig-mode: "0600"
tls-san:
  - ${TS_HOSTNAME}
EOF
}

k3s_node_ready() {
  local s
  s=$(kc get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}') || return 1
  [ -n "$s" ] && ! grep -qv '^True$' <<<"$s"
}

step_k3s() {
  local bin_name="k3s" dir="$CACHE_DIR/k3s-$K3S_VERSION" what=() tag_url cfg_dir=/etc/rancher/k3s
  local manifests=/var/lib/rancher/k3s/server/manifests tmp_cfg cfg_changed=0
  [ "$ARCH" = arm64 ] && bin_name="k3s-arm64"
  tag_url="${K3S_VERSION//+/%2B}"

  fetch_verified "https://github.com/k3s-io/k3s/releases/download/$tag_url/$bin_name" "$(pin_sha K3S)" "$dir/$bin_name"
  fetch_verified "https://raw.githubusercontent.com/k3s-io/k3s/$tag_url/install.sh" "$(pin_sha K3S_INSTALL)" "$dir/install.sh"

  # Config and the Traefik HelmChartConfig go in place before k3s (re)starts, so the first start already uses them.
  mkdir -p "$cfg_dir" "$manifests"
  tmp_cfg="$(mktemp)"
  k3s_config >"$tmp_cfg"
  if ! cmp -s "$tmp_cfg" "$cfg_dir/config.yaml"; then
    install -m 0600 "$tmp_cfg" "$cfg_dir/config.yaml"; what+=("config.yaml"); cfg_changed=1
  fi
  rm -f "$tmp_cfg"
  if ! cmp -s "$BOOTSTRAP_DIR/traefik-config.yaml" "$manifests/traefik-config.yaml"; then
    install -m 0600 "$BOOTSTRAP_DIR/traefik-config.yaml" "$manifests/traefik-config.yaml"; what+=("traefik ClusterIP")
  fi

  local installed=""
  [ -x "$K3S_BIN" ] && installed="$("$K3S_BIN" --version 2>/dev/null | head -n1)"
  if [[ "$installed" != *" $K3S_VERSION "* ]] || ! systemctl is-enabled --quiet k3s 2>/dev/null; then
    install -m 0755 "$dir/$bin_name" "$K3S_BIN"
    INSTALL_K3S_SKIP_DOWNLOAD=true INSTALL_K3S_VERSION="$K3S_VERSION" sh "$dir/install.sh" >&2 \
      || die "k3s install.sh failed"
    what+=("k3s $K3S_VERSION installed")
  elif [ "$cfg_changed" = 1 ]; then
    systemctl restart k3s || die "systemctl restart k3s failed"
  elif ! systemctl is-active --quiet k3s; then
    systemctl start k3s || die "systemctl start k3s failed"; what+=("k3s started")
  fi

  wait_until 300 "the k3s node to be Ready" k3s_node_ready
  if [ "${#what[@]}" -gt 0 ]; then status_changed k3s "$(IFS=,; echo "${what[*]}")"; else status_ok k3s; fi
}
