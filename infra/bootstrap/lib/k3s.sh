# shellcheck shell=bash
# Step k3s: pinned binary + install.sh (both sha256-verified), servicelb disabled, kubeconfig 0600, Traefik ClusterIP.
# A host that already runs a k3s bootstrap did not set up is refused (k3s_existing_decision) unless BOOTSTRAP_ADOPT=1
# (--adopt); a newer k3s than the pin is never downgraded.

# First line of every config.yaml bootstrap writes: how a later run recognises the file (and the k3s) as its own.
K3S_CONFIG_MARKER="# Managed by infra/bootstrap (lib/k3s.sh): rewritten on every run, local edits are lost."

k3s_config() {
  cat <<EOF
$K3S_CONFIG_MARKER
# No public LoadBalancer: servicelb is off and Traefik is ClusterIP. NodePorts answer on loopback only, so nothing
# reaches a pod through a node port from outside (NodePort traffic is forwarded, ufw's INPUT rules never see it).
disable:
  - servicelb
write-kubeconfig-mode: "0600"
tls-san:
  - ${TS_HOSTNAME}
kube-proxy-arg:
  - nodeport-addresses=127.0.0.1/32
EOF
}

# version_lt <a> <b>: true when k3s version a sorts before b (v1.36.9+k3s1 < v1.37.2+k3s1).
version_lt() {
  local lowest
  [ "$1" = "$2" ] && return 1
  lowest=$(printf '%s\n%s\n' "${1#v}" "${2#v}" | sort -V)
  [ "${lowest%%$'\n'*}" = "${1#v}" ]
}

# k3s_existing_decision <installed version or ""> <config: absent|ours|foreign> <adopt 0|1>
# Prints install (fresh host), keep (pinned version, ours), upgrade (older, ours or adopted) or refuse:<reason>.
k3s_existing_decision() {
  local installed="$1" config="$2" adopt="$3"
  if [ -n "$installed" ] && version_lt "$K3S_VERSION" "$installed"; then
    echo "refuse:k3s $installed is newer than the pinned $K3S_VERSION; k3s is never downgraded (raise K3S_VERSION in versions.env)"
    return
  fi
  if [ "$adopt" != 1 ]; then
    if [ "$config" = foreign ]; then
      echo "refuse:/etc/rancher/k3s/config.yaml was not written by bootstrap (no marker line); it would be replaced. Review it, then re-run with BOOTSTRAP_ADOPT=1 (--adopt)"
      return
    fi
    if [ -n "$installed" ] && [ "$config" = absent ]; then
      echo "refuse:k3s $installed is installed but was not set up by bootstrap; re-run with BOOTSTRAP_ADOPT=1 (--adopt) to take it over"
      return
    fi
  fi
  if [ -z "$installed" ]; then echo install
  elif [ "$installed" = "$K3S_VERSION" ]; then echo keep
  else echo upgrade; fi
}

# k3s_installed_version: the version of /usr/local/bin/k3s, or nothing.
k3s_installed_version() {
  local out tok
  [ -x "$K3S_BIN" ] || return 0
  # Captured whole, not piped into head: under pipefail an early-closing reader makes the writer die of SIGPIPE (141).
  out="$("$K3S_BIN" --version 2>/dev/null || true)"
  read -r _ _ tok _ <<<"${out%%$'\n'*}"
  echo "$tok"
}

k3s_config_kind() { # $1 = config path -> absent | ours | foreign
  local first
  if [ ! -e "$1" ]; then echo absent
  elif IFS= read -r first <"$1" && [ "${first%$'\r'}" = "$K3S_CONFIG_MARKER" ]; then echo ours
  else echo foreign; fi
}

k3s_node_ready() {
  local s
  s=$(kc get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}') || return 1
  [ -n "$s" ] && ! grep -qv '^True$' <<<"$s"
}

step_k3s() {
  local bin_name="k3s" dir="$CACHE_DIR/k3s-$K3S_VERSION" what=() tag_url cfg_dir=/etc/rancher/k3s
  local manifests=/var/lib/rancher/k3s/server/manifests tmp_cfg cfg_changed=0 installed kind decision
  [ "$ARCH" = arm64 ] && bin_name="k3s-arm64"
  tag_url="${K3S_VERSION//+/%2B}"

  installed="$(k3s_installed_version)"
  kind="$(k3s_config_kind "$cfg_dir/config.yaml")"
  decision="$(k3s_existing_decision "$installed" "$kind" "$BOOTSTRAP_ADOPT")"
  case "$decision" in refuse:*) die "${decision#refuse:}" ;; esac
  if [ "$kind" = foreign ]; then
    cp -p "$cfg_dir/config.yaml" "$cfg_dir/config.yaml.pre-bootstrap.$(date -u +%Y%m%dT%H%M%SZ)"
    note "k3s: adopting; the previous config.yaml was saved next to it"
  fi

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

  if [ "$decision" != keep ] || ! systemctl is-enabled --quiet k3s 2>/dev/null; then
    install -m 0755 "$dir/$bin_name" "$K3S_BIN"
    INSTALL_K3S_SKIP_DOWNLOAD=true INSTALL_K3S_VERSION="$K3S_VERSION" sh "$dir/install.sh" >&2 \
      || die "k3s install.sh failed"
    case "$decision" in
      install) what+=("k3s $K3S_VERSION installed") ;;
      upgrade) what+=("k3s upgraded $installed -> $K3S_VERSION") ;;
      *) what+=("k3s $K3S_VERSION reinstalled (service was not enabled)") ;;
    esac
  elif [ "$cfg_changed" = 1 ]; then
    systemctl restart k3s || die "systemctl restart k3s failed"
  elif ! systemctl is-active --quiet k3s; then
    systemctl start k3s || die "systemctl start k3s failed"; what+=("k3s started")
  fi

  wait_until 300 "the k3s node to be Ready" k3s_node_ready
  if [ "${#what[@]}" -gt 0 ]; then status_changed k3s "$(IFS=,; echo "${what[*]}")"; else status_ok k3s; fi
}
