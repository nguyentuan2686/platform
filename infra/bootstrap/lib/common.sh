# shellcheck shell=bash
# Shared helpers for infra/bootstrap/bootstrap.sh (sourced). Status lines go to stdout, everything else to stderr, so
# the stdout log is exactly the step summary that verify.sh --expect-unchanged reads.

CHANGED_COUNT=0
K3S_BIN=/usr/local/bin/k3s
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
# shellcheck disable=SC2034 # used by the other lib/*.sh
CACHE_DIR=/var/cache/platform-bootstrap

die() { echo "bootstrap: ERROR: $*" >&2; exit 1; }
note() { echo "  $*" >&2; }
status_changed() { echo "CHANGED $1: $2"; CHANGED_COUNT=$((CHANGED_COUNT + 1)); }
status_ok() { echo "OK $1"; }
status_skip() { echo "SKIP $1: $2"; }

kc() { "$K3S_BIN" kubectl "$@"; }

# pin_sha <NAME>: the NAME_SHA256_<arch> value from versions.env.
pin_sha() {
  local v="${1}_SHA256_${ARCH:?}"
  [ -n "${!v:-}" ] || die "versions.env has no $v"
  echo "${!v}"
}

# fetch_verified <url> <sha256> <dest>: download unless dest already has that sha256; verify before it lands.
fetch_verified() {
  local url="$1" sha="$2" dest="$3"
  if [ -f "$dest" ] && echo "$sha  $dest" | sha256sum -c --status - 2>/dev/null; then return 0; fi
  mkdir -p "$(dirname "$dest")"
  note "download $url"
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "$dest.part" "$url" || { rm -f "$dest.part"; die "download failed: $url"; }
  echo "$sha  $dest.part" | sha256sum -c --status - || { rm -f "$dest.part"; die "sha256 mismatch for $url (want $sha)"; }
  mv "$dest.part" "$dest"
}

# kube_sync <file> <field-manager> [namespace]: server-side diff, then server-side apply only when the diff reports a
# change (exit 1). Returns 0 when it applied, 1 when unchanged; a diff error (exit > 1) or apply error ends the script.
kube_sync() {
  local file="$1" fm="$2" ns_args=() rc=0
  [ -n "${3:-}" ] && ns_args=(-n "$3")
  kc diff --server-side --force-conflicts --field-manager="$fm" "${ns_args[@]}" -f "$file" >/dev/null || rc=$?
  case "$rc" in
    0) return 1 ;;
    1) kc apply --server-side --force-conflicts --field-manager="$fm" "${ns_args[@]}" -f "$file" >&2 \
         || die "kubectl apply failed for $file"
       return 0 ;;
    *) die "kubectl diff failed (exit $rc) for $file" ;;
  esac
}

# wait_until <seconds> <description> <command...>: deadline loop on observable state.
wait_until() {
  local secs="$1" what="$2" end
  shift 2
  end=$((SECONDS + secs))
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$end" ] || die "timed out after ${secs}s waiting for $what"
    sleep 3
  done
}

