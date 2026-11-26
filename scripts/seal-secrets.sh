#!/usr/bin/env bash
# Seal the prod Secrets listed in deploy/k8s/overlays/prod/secrets.tsv into SealedSecrets (offline, public cert only).
# Usage: scripts/seal-secrets.sh --cert <crt> [--in DIR] [--out DIR] [--only name,...] [--allow-test-values]
#        scripts/seal-secrets.sh --install-only      # install the pinned kubeseal into ~/.cache/platform/bin
#   --in   plaintext inputs, one <name>.env (KEY=value lines) per secret; default deploy/k8s/overlays/prod/secrets.local
#          (gitignored). --out default deploy/k8s/overlays/prod/sealed (writes <name>.sealedsecret.yaml and rewrites
#          kustomization.yaml `resources:` sorted, keeping its non-SealedSecret entries).
# Every input is checked before anything is written; any problem prints `SEAL: <reason>` and exits 1. Values are never
# printed. kubeseal: ~/.cache/platform/bin/kubeseal, else kubeseal on PATH, else the version pinned in
# infra/bootstrap/versions.env is downloaded and sha256-verified. The cert comes from `kubeseal --fetch-cert`
# (committed as deploy/k8s/overlays/prod/sealed/sealing-cert.crt).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tsv="$root/deploy/k8s/overlays/prod/secrets.tsv"
in="$root/deploy/k8s/overlays/prod/secrets.local"
out="$root/deploy/k8s/overlays/prod/sealed"
bin_dir="$HOME/.cache/platform/bin"
cert="" only="" allow_test=0 install_only=0

die() { echo "SEAL: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --cert) cert="${2:?--cert needs a file}"; shift 2 ;;
    --in) in="${2:?--in needs a directory}"; shift 2 ;;
    --out) out="${2:?--out needs a directory}"; shift 2 ;;
    --only) only="${2:?--only needs name,...}"; shift 2 ;;
    --allow-test-values) allow_test=1; shift ;;
    --install-only) install_only=1; shift ;;
    *) die "unknown argument $1 (see the usage at the top of $0)" ;;
  esac
done

install_kubeseal() {
  local arch sha tmpd url
  # shellcheck source=../infra/bootstrap/versions.env
  . "$root/infra/bootstrap/versions.env"
  [ "$(uname -s)" = Linux ] || die "kubeseal auto-install supports Linux only (run in alpine/k8s, see docs/runbooks/go-live.md)"
  case "$(uname -m)" in x86_64 | amd64) arch=amd64 ;; aarch64 | arm64) arch=arm64 ;; *) die "unsupported arch $(uname -m)" ;; esac
  sha="KUBESEAL_SHA256_$arch"; sha="${!sha:?}"
  url="https://github.com/bitnami-labs/sealed-secrets/releases/download/v${KUBESEAL_VERSION:?}/kubeseal-${KUBESEAL_VERSION}-linux-${arch}.tar.gz"
  tmpd="$(mktemp -d)"
  curl -fsSL -o "$tmpd/kubeseal.tar.gz" "$url" || { rm -rf "$tmpd"; die "download failed: $url"; }
  (cd "$tmpd" && echo "$sha  kubeseal.tar.gz" | sha256sum -c - >/dev/null) || { rm -rf "$tmpd"; die "kubeseal sha256 mismatch for $url"; }
  tar -xzf "$tmpd/kubeseal.tar.gz" -C "$tmpd" kubeseal
  mkdir -p "$bin_dir"
  install -m 0755 "$tmpd/kubeseal" "$bin_dir/kubeseal"
  rm -rf "$tmpd"
  echo "installed kubeseal $KUBESEAL_VERSION ($arch) into $bin_dir" >&2
}

if [ "$install_only" = 1 ]; then
  [ -x "$bin_dir/kubeseal" ] || install_kubeseal
  "$bin_dir/kubeseal" --version
  exit 0
fi

[ -n "$cert" ] || die "--cert <crt> is required (kubeseal --fetch-cert)"
[ -s "$cert" ] || die "cert $cert does not exist or is empty"
[ -d "$in" ] || die "input directory $in does not exist"
[ -f "$tsv" ] || die "inventory $tsv does not exist"
in="$(cd "$in" && pwd)"

# Inventory rows: name ns req keys (tab-separated, comments and blank lines skipped).
declare -A row_ns=() row_req=() row_keys=()
names=()
while IFS= read -r line; do
  case "$line" in '#'* | '') continue ;; esac
  IFS=$'\t' read -r name ns req keys extra <<<"$line"
  [ -n "$keys" ] && [ -z "${extra:-}" ] || die "secrets.tsv: malformed row: $name"
  case "$req" in required | optional) ;; *) die "secrets.tsv: $name: third column must be required|optional" ;; esac
  row_ns[$name]="$ns"; row_req[$name]="$req"; row_keys[$name]="$keys"; names+=("$name")
done < <(tr -d '\r' <"$tsv")

declare -A wanted=()
if [ -n "$only" ]; then
  for n in ${only//,/ }; do
    [ -n "${row_ns[$n]:-}" ] || die "--only $n: not a row of secrets.tsv"
    wanted[$n]=1
  done
fi

problems=0
problem() { echo "SEAL: $*" >&2; problems=$((problems + 1)); }

# Inside a Git work tree, the input must be gitignored and no input file may be tracked.
in_repo=0
git -C "$in" rev-parse --is-inside-work-tree >/dev/null 2>&1 && in_repo=1

selected=()
for n in "${names[@]}"; do
  f="$in/$n.env"
  if [ -n "$only" ] && [ -z "${wanted[$n]:-}" ]; then continue; fi
  if [ ! -f "$f" ]; then
    if [ -n "$only" ]; then problem "$n: --only names it but $f does not exist"
    elif [ "${row_req[$n]}" = required ]; then problem "$n: required secret has no input $f"; fi
    continue
  fi
  if [ "$in_repo" = 1 ]; then
    if git -C "$in" ls-files --error-unmatch -- "$n.env" >/dev/null 2>&1; then problem "$n: input $f is tracked by Git"; continue; fi
    if ! git -C "$in" check-ignore -q -- "$n.env"; then problem "$n: input $f is inside a Git work tree and not gitignored"; continue; fi
  fi
  declare -A seen=()
  lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    line="${line%$'\r'}"
    case "$line" in '#'* | '') continue ;; esac
    if [[ "$line" != *=* ]]; then problem "$n: line $lineno is not KEY=value"; continue; fi
    k="${line%%=*}"; v="${line#*=}"
    if [ -n "${seen[$k]:-}" ]; then problem "$n: key $k appears twice"; continue; fi
    seen[$k]=1
    if [ -z "$v" ]; then problem "$n: key $k has an empty value"
    elif [ "$allow_test" = 0 ] && [[ "$v" == local-test-* ]]; then problem "$n: key $k has a local-test- value (use --allow-test-values for CI only)"; fi
  done <"$f"
  have=$(printf '%s\n' "${!seen[@]}" | sort)
  want=$(tr ',' '\n' <<<"${row_keys[$n]}" | sort)
  if [ "$have" != "$want" ]; then
    problem "$n: key set differs from secrets.tsv: missing [$(comm -13 <(echo "$have") <(echo "$want") | paste -sd, -)] extra [$(comm -23 <(echo "$have") <(echo "$want") | paste -sd, -)]"
  fi
  unset seen
  selected+=("$n")
done
[ "$problems" -eq 0 ] || die "$problems problem(s), nothing written"
[ "${#selected[@]}" -gt 0 ] || die "no input files in $in"

# Existing kustomization: only apiVersion, kind and a plain resources list are understood (fail closed otherwise).
kfile="$out/kustomization.yaml"
mkdir -p "$out"
keep=()
header=""
if [ -f "$kfile" ]; then
  unknown=$(tr -d '\r' <"$kfile" | grep -E '^[A-Za-z]' | grep -Ev '^(apiVersion|kind|resources):' || true)
  [ -z "$unknown" ] || die "$kfile has fields this script cannot preserve: $unknown"
  header=$(tr -d '\r' <"$kfile" | sed -n '/^[^#]/q; p')
  while IFS= read -r r; do
    [[ "$r" == *.sealedsecret.yaml ]] || keep+=("$r")
  done < <(tr -d '\r' <"$kfile" | sed -n 's/^  - //p' | sed 's/[[:space:]]*#.*$//')
fi

# kubeseal binary (see the header).
if [ -x "$bin_dir/kubeseal" ]; then kubeseal="$bin_dir/kubeseal"
elif command -v kubeseal >/dev/null 2>&1; then kubeseal="$(command -v kubeseal)"
else install_kubeseal; kubeseal="$bin_dir/kubeseal"; fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
for n in "${selected[@]}"; do
  {
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\ndata:\n' "$n" "${row_ns[$n]}"
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in '#'* | '') continue ;; esac
      printf '  %s: %s\n' "${line%%=*}" "$(printf '%s' "${line#*=}" | base64 | tr -d '\n')"
    done <"$in/$n.env"
  } >"$work/$n.secret.yaml"
  "$kubeseal" --cert "$cert" --scope strict --format yaml <"$work/$n.secret.yaml" >"$work/$n.sealedsecret.yaml" \
    || die "$n: kubeseal failed"
  grep -q '^kind: SealedSecret$' "$work/$n.sealedsecret.yaml" || die "$n: kubeseal output is not a SealedSecret"
  rm -f "$work/$n.secret.yaml"
done
for n in "${selected[@]}"; do
  mv "$work/$n.sealedsecret.yaml" "$out/$n.sealedsecret.yaml"
  echo "sealed $n (${row_ns[$n]}) -> $out/$n.sealedsecret.yaml"
done

mapfile -t resources < <({ printf '%s\n' "${keep[@]+"${keep[@]}"}"; (cd "$out" && ls -1 -- *.sealedsecret.yaml); } | sed '/^$/d' | LC_ALL=C sort -u)
{
  [ -n "$header" ] && printf '%s\n' "$header"
  printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n'
  printf '  - %s\n' "${resources[@]}"
} >"$kfile"
echo "wrote $kfile (${#resources[@]} resources)"
