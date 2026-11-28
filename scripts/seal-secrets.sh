#!/usr/bin/env bash
# Seal the prod Secrets listed in deploy/k8s/overlays/prod/secrets.tsv into SealedSecrets (offline, public cert only).
# Usage: scripts/seal-secrets.sh --cert <crt> [--in DIR] [--out DIR] [--only name,...] [--allow-test-values]
#        scripts/seal-secrets.sh --install-only      # install the pinned kubeseal into ~/.cache/platform/bin
#   --in   plaintext inputs, one <name>.env (KEY=value lines) per secret; default deploy/k8s/overlays/prod/secrets.local
#          (gitignored). --out default deploy/k8s/overlays/prod/sealed (writes <name>.sealedsecret.yaml and rewrites
#          kustomization.yaml `resources:` sorted, keeping its non-SealedSecret entries; parsed with yq, never shrunk).
# Every input is checked before anything is written; any problem prints `SEAL: <reason>` and exits 1. Values are never
# printed and the plaintext Secret is piped into kubeseal, never written to disk. Needs git (the input must sit,
# untracked and gitignored, inside a Git work tree) and yq. A key marked `?` in secrets.tsv may have an empty value.
# kubeseal: ~/.cache/platform/bin/kubeseal (reinstalled when it is not the pinned version), else kubeseal on PATH, else
# the version pinned in infra/bootstrap/versions.env is downloaded and sha256-verified. The cert comes from
# `kubeseal --fetch-cert` (committed as deploy/k8s/overlays/prod/sealed/sealing-cert.crt).
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

# shellcheck source=../infra/bootstrap/versions.env
. "$root/infra/bootstrap/versions.env"

install_kubeseal() {
  local arch sha tmpd url
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

# A cached kubeseal that is not the pinned version is replaced.
cached_is_pinned() {
  local v
  [ -x "$bin_dir/kubeseal" ] && v="$("$bin_dir/kubeseal" --version 2>/dev/null)" && grep -qF "$KUBESEAL_VERSION" <<<"$v"
}

if [ "$install_only" = 1 ]; then
  cached_is_pinned || install_kubeseal
  "$bin_dir/kubeseal" --version
  exit 0
fi

[ -n "$cert" ] || die "--cert <crt> is required (kubeseal --fetch-cert)"
[ -s "$cert" ] || die "cert $cert does not exist or is empty"
[ -d "$in" ] || die "input directory $in does not exist"
[ -f "$tsv" ] || die "inventory $tsv does not exist"
in="$(cd "$in" && pwd)"
# The tracked / not-gitignored refusals must never be skipped: git and a Git work tree around the input are required.
command -v git >/dev/null 2>&1 || die "git is required (inputs must be checked as untracked and gitignored)"
command -v yq >/dev/null 2>&1 || die "yq is required (to edit $out/kustomization.yaml safely)"
[ "$(git -C "$in" rev-parse --is-inside-work-tree 2>/dev/null || true)" = true ] \
  || die "input directory $in is not inside a Git work tree (use the gitignored deploy/k8s/overlays/prod/secrets.local)"

# Inventory rows: name ns req keys (tab-separated, comments and blank lines skipped). A key ending in `?` may be empty
# (e.g. an LLM provider key: empty disables that provider), but it must be present.
declare -A row_ns=() row_req=() row_keys=() may_be_empty=()
names=()
while IFS= read -r line; do
  case "$line" in '#'* | '') continue ;; esac
  IFS=$'\t' read -r name ns req keys extra <<<"$line"
  [ -n "$keys" ] && [ -z "${extra:-}" ] || die "secrets.tsv: malformed row: $name"
  case "$req" in required | optional) ;; *) die "secrets.tsv: $name: third column must be required|optional" ;; esac
  IFS=, read -ra ks <<<"$keys"
  plain=()
  for k in "${ks[@]}"; do
    if [[ "$k" == *'?' ]]; then k="${k%'?'}"; may_be_empty["$name/$k"]=1; fi
    plain+=("$k")
  done
  row_ns[$name]="$ns"; row_req[$name]="$req"; row_keys[$name]="$(IFS=,; echo "${plain[*]}")"; names+=("$name")
done < <(tr -d '\r' <"$tsv")

declare -A wanted=()
if [ -n "$only" ]; then
  IFS=, read -ra only_names <<<"$only"
  for n in "${only_names[@]}"; do
    [ -n "${row_ns[$n]:-}" ] || die "--only $n: not a row of secrets.tsv"
    wanted[$n]=1
  done
fi

problems=0
problem() { echo "SEAL: $*" >&2; problems=$((problems + 1)); }

selected=()
for n in "${names[@]}"; do
  f="$in/$n.env"
  if [ -n "$only" ] && [ -z "${wanted[$n]:-}" ]; then continue; fi
  if [ ! -f "$f" ]; then
    if [ -n "$only" ]; then problem "$n: --only names it but $f does not exist"
    elif [ "${row_req[$n]}" = required ]; then problem "$n: required secret has no input $f"; fi
    continue
  fi
  if git -C "$in" ls-files --error-unmatch -- "$n.env" >/dev/null 2>&1; then problem "$n: input $f is tracked by Git"; continue; fi
  if ! git -C "$in" check-ignore -q -- "$n.env"; then problem "$n: input $f is inside a Git work tree and not gitignored"; continue; fi
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
    if [ -z "$v" ]; then
      [ -n "${may_be_empty["$n/$k"]:-}" ] || problem "$n: key $k has an empty value (only keys marked ? in secrets.tsv may be empty)"
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

# Existing kustomization, parsed with yq: only apiVersion, kind and a list of string resources are understood; anything
# else is refused. Entries are never dropped (dropping ../../../platform/namespaces would prune the namespaces).
kfile="$out/kustomization.yaml"
mkdir -p "$out"
keep=() old=() new=()
header=""
if [ -f "$kfile" ]; then
  unknown=$(yq -r 'keys | .[] | select(. != "apiVersion" and . != "kind" and . != "resources")' "$kfile") \
    || die "$kfile is not valid YAML"
  [ -z "$unknown" ] || die "$kfile has fields this script cannot preserve: $(paste -sd, - <<<"$unknown")"
  [ "$(yq -r '(.resources // []) | type' "$kfile")" = '!!seq' ] || die "$kfile: resources is not a list"
  [ "$(yq -r '[(.resources // [])[] | select(type != "!!str")] | length' "$kfile")" = 0 ] || die "$kfile: resources has non-string entries"
  mapfile -t old < <(yq -r '(.resources // [])[]' "$kfile" | tr -d '\r')
  for r in "${old[@]+"${old[@]}"}"; do [[ "$r" == *.sealedsecret.yaml ]] || keep+=("$r"); done
  # sed quits at the first non-comment line: feed it a captured string, never a live pipe (SIGPIPE, exit 141).
  kraw=$(tr -d '\r' <"$kfile")
  header=$(sed -n '/^[^#]/q; p' <<<"$kraw")
fi

# kubeseal binary (see the header).
if [ -x "$bin_dir/kubeseal" ] && ! cached_is_pinned; then install_kubeseal; fi
if [ -x "$bin_dir/kubeseal" ]; then kubeseal="$bin_dir/kubeseal"
elif command -v kubeseal >/dev/null 2>&1; then kubeseal="$(command -v kubeseal)"
else install_kubeseal; kubeseal="$bin_dir/kubeseal"; fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
for n in "${selected[@]}"; do
  # The plaintext Secret goes through a pipe only.
  {
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\ndata:\n' "$n" "${row_ns[$n]}"
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in '#'* | '') continue ;; esac
      printf '  %s: "%s"\n' "${line%%=*}" "$(printf '%s' "${line#*=}" | base64 | tr -d '\n')"
    done <"$in/$n.env"
  } | "$kubeseal" --cert "$cert" --scope strict --format yaml >"$work/$n.sealedsecret.yaml" || die "$n: kubeseal failed"
  grep -q '^kind: SealedSecret$' "$work/$n.sealedsecret.yaml" || die "$n: kubeseal output is not a SealedSecret"
done

# Build and check the new kustomization before touching --out.
mapfile -t resources < <({ printf '%s\n' "${keep[@]+"${keep[@]}"}" "${selected[@]/%/.sealedsecret.yaml}"
  (cd "$out" && ls -1 -- *.sealedsecret.yaml 2>/dev/null || true); } | sed '/^$/d' | LC_ALL=C sort -u)
{
  [ -n "$header" ] && printf '%s\n' "$header"
  printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n'
  printf '  - %s\n' "${resources[@]}"
} >"$work/kustomization.yaml"
mapfile -t new < <(yq -r '.resources[]' "$work/kustomization.yaml" | tr -d '\r')
for r in "${old[@]+"${old[@]}"}"; do
  grep -qxF -- "$r" <<<"$(printf '%s\n' "${new[@]}")" || die "internal: the rewrite would drop resource $r from $kfile (nothing written)"
done

for n in "${selected[@]}"; do
  mv "$work/$n.sealedsecret.yaml" "$out/$n.sealedsecret.yaml"
  echo "sealed $n (${row_ns[$n]}) -> $out/$n.sealedsecret.yaml"
done
mv "$work/kustomization.yaml" "$kfile"
echo "wrote $kfile (${#resources[@]} resources)"
