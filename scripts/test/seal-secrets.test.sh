#!/usr/bin/env bash
# Tests for scripts/seal-secrets.sh. Prints "ok <case>" per case; exits non-zero on first failure.
# Offline: a throwaway self-signed cert (openssl) and `kubeseal --cert`; no cluster, no network
# (kubeseal must already be on PATH or in ~/.cache/platform/bin: CI installs it with `seal-secrets.sh --install-only`).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/seal-secrets.sh"
tsv="$root/deploy/k8s/overlays/prod/secrets.tsv"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=local-test -days 2 \
  -keyout "$tmp/throwaway.key" -out "$tmp/cert.crt" >/dev/null 2>&1 || fail setup "openssl could not create the throwaway cert"

# Writes <dir>/<name>.env for every row of secrets.tsv (or only the given names) with non-test values.
fill_env() { # $1 = dir, $2.. = names (default: required rows)
  local dir="$1" name req keys k; shift
  mkdir -p "$dir"
  while IFS=$'\t' read -r name _ req keys; do
    case "$name" in '#'* | '') continue ;; esac
    if [ $# -gt 0 ]; then [[ " $* " == *" $name "* ]] || continue; else [ "$req" = required ] || continue; fi
    : >"$dir/$name.env"
    for k in ${keys//,/ }; do printf '%s=v-%s-%s\n' "$k" "$name" "$RANDOM" >>"$dir/$name.env"; done
  done < <(tr -d '\r' <"$tsv")
}

required_names() { tr -d '\r' <"$tsv" | awk -F'\t' '!/^#/ && NF == 4 && $3 == "required" {print $1}' | sort; }

seals_all_required_rows() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" n got want
  fill_env "$in"; mkdir -p "$out"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 \
    || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out.log")"
  want=$(required_names)
  got=$(cd "$out" && ls ./*.sealedsecret.yaml | sed 's|^\./||; s|\.sealedsecret\.yaml$||' | sort)
  [ "$got" = "$want" ] || fail "${FUNCNAME[0]}" "sealed files: got [$got] want [$want]"
  for n in $want; do
    grep -q '^kind: SealedSecret$' "$out/$n.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "$n: not a SealedSecret"
    grep -q "^  name: $n\$" "$out/$n.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "$n: wrong name"
    if grep -q "v-$n-" "$out/$n.sealedsecret.yaml"; then fail "${FUNCNAME[0]}" "$n: plaintext value in output"; fi
  done
  grep -q '^  namespace: data$' "$out/postgres-secrets.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "postgres-secrets not in ns data"
  echo "ok ${FUNCNAME[0]}"
}

key_set_mismatch_refused() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" rc=0
  fill_env "$in"; mkdir -p "$out"
  echo "EXTRA_KEY=v-extra" >>"$in/order-svc-secrets.env"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*order-svc-secrets.*EXTRA_KEY' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  [ -z "$(ls "$out")" ] || fail "${FUNCNAME[0]}" "a refused run wrote files: $(ls "$out")"
  echo "ok ${FUNCNAME[0]}"
}

test_value_refused_without_flag() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" rc=0
  fill_env "$in"; mkdir -p "$out"
  sed -i 's/^DB_PASSWORD=.*/DB_PASSWORD=local-test-db-password/' "$in/order-svc-secrets.env"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*order-svc-secrets.*DB_PASSWORD' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  if grep -q 'local-test-db-password' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "the value was printed"; fi
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" --allow-test-values >"$tmp/out.log" 2>&1 \
    || fail "${FUNCNAME[0]}" "--allow-test-values: expected exit 0: $(cat "$tmp/out.log")"
  # an empty value is refused even with --allow-test-values
  sed -i 's/^DB_USER=.*/DB_USER=/' "$in/order-svc-secrets.env"
  rc=0; bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" --allow-test-values >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] && grep -q '^SEAL: .*order-svc-secrets.*DB_USER' "$tmp/out.log" || fail "${FUNCNAME[0]}" "empty value accepted: $(cat "$tmp/out.log")"
  echo "ok ${FUNCNAME[0]}"
}

missing_required_refused_unless_only() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" rc=0
  fill_env "$in" order-svc-secrets; mkdir -p "$out"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] && grep -q '^SEAL: .*web-secrets' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing required row accepted: $(cat "$tmp/out.log")"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" --only order-svc-secrets >"$tmp/out.log" 2>&1 \
    || fail "${FUNCNAME[0]}" "--only: expected exit 0: $(cat "$tmp/out.log")"
  set -- "$out"/*.sealedsecret.yaml; [ $# -eq 1 ] || fail "${FUNCNAME[0]}" "--only sealed more than one: $(ls "$out")"
  echo "ok ${FUNCNAME[0]}"
}

tracked_input_refused() {
  local repo="$tmp/${FUNCNAME[0]}/repo" rc=0
  mkdir -p "$repo/secrets.local" "$repo/sealed"
  git -C "$repo" init -q
  fill_env "$repo/secrets.local" order-svc-secrets
  git -C "$repo" add secrets.local/order-svc-secrets.env
  git -C "$repo" -c user.name=t -c user.email=t@t.invalid commit -q -m tracked
  bash "$script" --cert "$tmp/cert.crt" --in "$repo/secrets.local" --out "$repo/sealed" --only order-svc-secrets >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*tracked by Git' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  # untracked but not gitignored inside a repo: refused too
  git -C "$repo" rm -q --cached secrets.local/order-svc-secrets.env
  rc=0; bash "$script" --cert "$tmp/cert.crt" --in "$repo/secrets.local" --out "$repo/sealed" --only order-svc-secrets >"$tmp/out.log" 2>&1 || rc=$?
  [ "$rc" -eq 1 ] && grep -q '^SEAL: .*not gitignored' "$tmp/out.log" || fail "${FUNCNAME[0]}" "unignored input accepted: $(cat "$tmp/out.log")"
  # gitignored: accepted
  echo "secrets.local/" >"$repo/.gitignore"
  bash "$script" --cert "$tmp/cert.crt" --in "$repo/secrets.local" --out "$repo/sealed" --only order-svc-secrets >"$tmp/out.log" 2>&1 \
    || fail "${FUNCNAME[0]}" "gitignored input refused: $(cat "$tmp/out.log")"
  echo "ok ${FUNCNAME[0]}"
}

kustomization_resources_sorted() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" got want
  fill_env "$in"; mkdir -p "$out"
  # an existing non-sealed entry (the namespaces) is kept
  printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - namespace-edge.yaml\n  - ../../../platform/namespaces\n' >"$out/kustomization.yaml"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 \
    || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out.log")"
  got=$(sed -n 's/^  - //p' "$out/kustomization.yaml")
  want=$( { echo namespace-edge.yaml; echo ../../../platform/namespaces; required_names | sed 's/$/.sealedsecret.yaml/'; } | LC_ALL=C sort)
  [ "$got" = "$want" ] || fail "${FUNCNAME[0]}" "resources: got [$got] want [$want]"
  echo "ok ${FUNCNAME[0]}"
}

rerun_rewrites_same_files() {
  local in="$tmp/${FUNCNAME[0]}/in" out="$tmp/${FUNCNAME[0]}/out" first second
  fill_env "$in"; mkdir -p "$out"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 || fail "${FUNCNAME[0]}" "run 1: $(cat "$tmp/out.log")"
  first=$(cd "$out" && ls -1 | sort); cp "$out/web-secrets.sealedsecret.yaml" "$tmp/web.1"
  bash "$script" --cert "$tmp/cert.crt" --in "$in" --out "$out" >"$tmp/out.log" 2>&1 || fail "${FUNCNAME[0]}" "run 2: $(cat "$tmp/out.log")"
  second=$(cd "$out" && ls -1 | sort)
  [ "$first" = "$second" ] || fail "${FUNCNAME[0]}" "file set changed: [$first] vs [$second]"
  if cmp -s "$tmp/web.1" "$out/web-secrets.sealedsecret.yaml"; then fail "${FUNCNAME[0]}" "ciphertext identical (expected fresh session keys)"; fi
  echo "ok ${FUNCNAME[0]}"
}

seals_all_required_rows
key_set_mismatch_refused
test_value_refused_without_flag
missing_required_refused_unless_only
tracked_input_refused
kustomization_resources_sorted
rerun_rewrites_same_files
