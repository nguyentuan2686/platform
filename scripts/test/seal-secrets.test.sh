#!/usr/bin/env bash
# Tests for scripts/seal-secrets.sh. Prints "ok <case>" per case; exits non-zero on first failure.
# Offline: a throwaway self-signed cert (openssl) and `kubeseal --cert`; no cluster, no network
# (kubeseal must already be on PATH or in ~/.cache/platform/bin: CI installs it with `seal-secrets.sh --install-only`).
# Inputs live in throwaway Git repos whose secrets.local/ is gitignored, as in the real repo.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/seal-secrets.sh"
tsv="$root/deploy/k8s/overlays/prod/secrets.tsv"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=local-test -days 2 \
  -keyout "$tmp/throwaway.key" -out "$tmp/cert.crt" >/dev/null 2>&1 || fail setup "openssl could not create the throwaway cert"

# Creates $tmp/<case>/repo (git, secrets.local/ gitignored); prints the repo path.
mkrepo() { # $1 = case
  local repo="$tmp/$1/repo"
  mkdir -p "$repo/secrets.local" "$repo/sealed"
  git -C "$repo" init -q
  echo "secrets.local/" >"$repo/.gitignore"
  echo "$repo"
}

# Writes <dir>/<name>.env for every required row of secrets.tsv (or only the given names) with non-test values.
fill_env() { # $1 = dir, $2.. = names (default: required rows)
  local dir="$1" name req keys k; shift
  mkdir -p "$dir"
  while IFS=$'\t' read -r name _ req keys; do
    case "$name" in '#'* | '') continue ;; esac
    if [ $# -gt 0 ]; then [[ " $* " == *" $name "* ]] || continue; else [ "$req" = required ] || continue; fi
    : >"$dir/$name.env"
    for k in ${keys//,/ }; do printf '%s=v-%s-%s\n' "${k%'?'}" "$name" "$RANDOM" >>"$dir/$name.env"; done
  done < <(tr -d '\r' <"$tsv")
}

required_names() { tr -d '\r' <"$tsv" | awk -F'\t' '!/^#/ && NF == 4 && $3 == "required" {print $1}' | sort; }

seal() { # $1 = repo, rest = extra args; output in $tmp/out.log
  local repo="$1"; shift
  bash "$script" --cert "$tmp/cert.crt" --in "$repo/secrets.local" --out "$repo/sealed" "$@" >"$tmp/out.log" 2>&1
}

seals_all_required_rows() {
  local repo n got want
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  seal "$repo" || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out.log")"
  want=$(required_names)
  got=$(find "$repo/sealed" -name '*.sealedsecret.yaml' -exec basename {} .sealedsecret.yaml \; | sort)
  [ "$got" = "$want" ] || fail "${FUNCNAME[0]}" "sealed files: got [$got] want [$want]"
  for n in $want; do
    grep -q '^kind: SealedSecret$' "$repo/sealed/$n.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "$n: not a SealedSecret"
    grep -q "^  name: $n\$" "$repo/sealed/$n.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "$n: wrong name"
    if grep -q "v-$n-" "$repo/sealed/$n.sealedsecret.yaml"; then fail "${FUNCNAME[0]}" "$n: plaintext value in output"; fi
  done
  grep -q '^  namespace: data$' "$repo/sealed/postgres-secrets.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "postgres-secrets not in ns data"
  echo "ok ${FUNCNAME[0]}"
}

key_set_mismatch_refused() {
  local repo rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  echo "EXTRA_KEY=v-extra" >>"$repo/secrets.local/order-svc-secrets.env"
  seal "$repo" || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*order-svc-secrets.*EXTRA_KEY' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  [ -z "$(ls "$repo/sealed")" ] || fail "${FUNCNAME[0]}" "a refused run wrote files: $(ls "$repo/sealed")"
  echo "ok ${FUNCNAME[0]}"
}

test_value_refused_without_flag() {
  local repo rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  sed -i 's/^DB_PASSWORD=.*/DB_PASSWORD=local-test-db-password/' "$repo/secrets.local/order-svc-secrets.env"
  seal "$repo" || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*order-svc-secrets.*DB_PASSWORD' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  if grep -q 'local-test-db-password' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "the value was printed"; fi
  seal "$repo" --allow-test-values || fail "${FUNCNAME[0]}" "--allow-test-values: expected exit 0: $(cat "$tmp/out.log")"
  # an empty value of an unmarked key is refused even with --allow-test-values
  sed -i 's/^DB_USER=.*/DB_USER=/' "$repo/secrets.local/order-svc-secrets.env"
  rc=0; seal "$repo" --allow-test-values || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: .*order-svc-secrets.*DB_USER' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "empty value accepted: $(cat "$tmp/out.log")"; fi
  echo "ok ${FUNCNAME[0]}"
}

optional_empty_key_allowed() {
  local repo rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  # GROQ_API_KEY? / GEMINI_API_KEY? / OIDC_GOOGLE_CLIENT_SECRET? may be empty ...
  sed -i 's/^GROQ_API_KEY=.*/GROQ_API_KEY=/; s/^GEMINI_API_KEY=.*/GEMINI_API_KEY=/' "$repo/secrets.local/reading-tuvi-secrets.env"
  sed -i 's/^OIDC_GOOGLE_CLIENT_SECRET=.*/OIDC_GOOGLE_CLIENT_SECRET=/' "$repo/secrets.local/identity-svc-secrets.env"
  seal "$repo" || fail "${FUNCNAME[0]}" "empty ?-keys refused: $(cat "$tmp/out.log")"
  grep -q '^    GROQ_API_KEY: ' "$repo/sealed/reading-tuvi-secrets.sealedsecret.yaml" || fail "${FUNCNAME[0]}" "empty key not sealed"
  # ... but must be present
  sed -i '/^GEMINI_API_KEY=/d' "$repo/secrets.local/reading-tuvi-secrets.env"
  seal "$repo" || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: reading-tuvi-secrets: key set differs.*missing \[GEMINI_API_KEY\]' "$tmp/out.log"; then
    fail "${FUNCNAME[0]}" "missing ?-key accepted: $(cat "$tmp/out.log")"
  fi
  echo "ok ${FUNCNAME[0]}"
}

missing_required_refused_unless_only() {
  local repo rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local" order-svc-secrets
  seal "$repo" || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: .*web-secrets' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "missing required row accepted: $(cat "$tmp/out.log")"; fi
  seal "$repo" --only order-svc-secrets || fail "${FUNCNAME[0]}" "--only: expected exit 0: $(cat "$tmp/out.log")"
  set -- "$repo"/sealed/*.sealedsecret.yaml
  [ $# -eq 1 ] || fail "${FUNCNAME[0]}" "--only sealed more than one: $(ls "$repo/sealed")"
  echo "ok ${FUNCNAME[0]}"
}

tracked_input_refused() {
  local repo rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local" order-svc-secrets
  git -C "$repo" add -f secrets.local/order-svc-secrets.env
  git -C "$repo" -c user.name=t -c user.email=t@t.invalid commit -q -m tracked
  seal "$repo" --only order-svc-secrets || rc=$?
  [ "$rc" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $rc: $(cat "$tmp/out.log")"
  grep -q '^SEAL: .*tracked by Git' "$tmp/out.log" || fail "${FUNCNAME[0]}" "missing SEAL line: $(cat "$tmp/out.log")"
  # untracked but not gitignored: refused too
  git -C "$repo" rm -q --cached secrets.local/order-svc-secrets.env
  rm "$repo/.gitignore"
  rc=0; seal "$repo" --only order-svc-secrets || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: .*not gitignored' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "unignored input accepted: $(cat "$tmp/out.log")"; fi
  # gitignored again: accepted
  echo "secrets.local/" >"$repo/.gitignore"
  seal "$repo" --only order-svc-secrets || fail "${FUNCNAME[0]}" "gitignored input refused: $(cat "$tmp/out.log")"
  echo "ok ${FUNCNAME[0]}"
}

input_outside_work_tree_refused() {
  local dir="$tmp/${FUNCNAME[0]}" rc=0
  fill_env "$dir/in"; mkdir -p "$dir/out"
  bash "$script" --cert "$tmp/cert.crt" --in "$dir/in" --out "$dir/out" >"$tmp/out.log" 2>&1 || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: .*not inside a Git work tree' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "accepted: $(cat "$tmp/out.log")"; fi
  [ -z "$(ls "$dir/out")" ] || fail "${FUNCNAME[0]}" "wrote files"
  echo "ok ${FUNCNAME[0]}"
}

git_missing_refused() {
  local repo bin="$tmp/${FUNCNAME[0]}/bin" d f rc=0
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  # a PATH with every command except git
  mkdir -p "$bin"
  IFS=: read -ra dirs <<<"$PATH"
  for d in "${dirs[@]}"; do
    for f in "$d"/*; do
      [ -x "$f" ] && [ ! -e "$bin/${f##*/}" ] && [ "${f##*/}" != git ] && ln -s "$f" "$bin/${f##*/}"
    done
  done
  [ -x "$HOME/.cache/platform/bin/kubeseal" ] && ln -sf "$HOME/.cache/platform/bin/kubeseal" "$bin/kubeseal"
  PATH="$bin" bash "$script" --cert "$tmp/cert.crt" --in "$repo/secrets.local" --out "$repo/sealed" >"$tmp/out.log" 2>&1 || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: git is required' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "accepted without git: $(cat "$tmp/out.log")"; fi
  [ -z "$(ls "$repo/sealed")" ] || fail "${FUNCNAME[0]}" "wrote files"
  echo "ok ${FUNCNAME[0]}"
}

kustomization_resources_sorted() {
  local repo got want
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  # existing non-sealed entries (the namespaces) are kept
  printf '# header kept\napiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - namespace-edge.yaml\n  - ../../../platform/namespaces\n' >"$repo/sealed/kustomization.yaml"
  seal "$repo" || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out.log")"
  got=$(sed -n 's/^  - //p' "$repo/sealed/kustomization.yaml")
  want=$( { echo namespace-edge.yaml; echo ../../../platform/namespaces; required_names | sed 's/$/.sealedsecret.yaml/'; } | LC_ALL=C sort)
  [ "$got" = "$want" ] || fail "${FUNCNAME[0]}" "resources: got [$got] want [$want]"
  head -n 1 "$repo/sealed/kustomization.yaml" | grep -qx '# header kept' || fail "${FUNCNAME[0]}" "header comment lost"
  echo "ok ${FUNCNAME[0]}"
}

kustomization_other_indent_preserved() {
  local repo style got want
  for style in zero four flow; do
    repo=$(mkrepo "${FUNCNAME[0]}-$style"); fill_env "$repo/secrets.local"
    case "$style" in
      zero) printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n- ../../../platform/namespaces\n- namespace-edge.yaml # edge\n' ;;
      four) printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n    - ../../../platform/namespaces\n    - namespace-edge.yaml\n' ;;
      flow) printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [../../../platform/namespaces, namespace-edge.yaml]\n' ;;
    esac >"$repo/sealed/kustomization.yaml"
    seal "$repo" || fail "${FUNCNAME[0]}" "$style: expected exit 0: $(cat "$tmp/out.log")"
    got=$(yq -r '.resources[]' "$repo/sealed/kustomization.yaml")
    want=$( { echo namespace-edge.yaml; echo ../../../platform/namespaces; required_names | sed 's/$/.sealedsecret.yaml/'; } | LC_ALL=C sort)
    [ "$got" = "$want" ] || fail "${FUNCNAME[0]}" "$style: resources: got [$got] want [$want]"
  done
  echo "ok ${FUNCNAME[0]}"
}

kustomization_unknown_shape_refused() {
  local repo rc=0 before
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - namespace-edge.yaml\npatches:\n  - path: p.yaml\n' >"$repo/sealed/kustomization.yaml"
  before=$(cat "$repo/sealed/kustomization.yaml")
  seal "$repo" || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -q '^SEAL: .*cannot preserve: patches' "$tmp/out.log"; then fail "${FUNCNAME[0]}" "accepted: $(cat "$tmp/out.log")"; fi
  [ "$(cat "$repo/sealed/kustomization.yaml")" = "$before" ] || fail "${FUNCNAME[0]}" "kustomization changed"
  set -- "$repo"/sealed/*.sealedsecret.yaml
  [ ! -e "$1" ] || fail "${FUNCNAME[0]}" "sealed files written by a refused run"
  echo "ok ${FUNCNAME[0]}"
}

rerun_rewrites_same_files() {
  local repo first second
  repo=$(mkrepo "${FUNCNAME[0]}"); fill_env "$repo/secrets.local"
  seal "$repo" || fail "${FUNCNAME[0]}" "run 1: $(cat "$tmp/out.log")"
  first=$(find "$repo/sealed" -type f | sort); cp "$repo/sealed/web-secrets.sealedsecret.yaml" "$tmp/web.1"
  seal "$repo" || fail "${FUNCNAME[0]}" "run 2: $(cat "$tmp/out.log")"
  second=$(find "$repo/sealed" -type f | sort)
  [ "$first" = "$second" ] || fail "${FUNCNAME[0]}" "file set changed: [$first] vs [$second]"
  if cmp -s "$tmp/web.1" "$repo/sealed/web-secrets.sealedsecret.yaml"; then fail "${FUNCNAME[0]}" "ciphertext identical (expected fresh session keys)"; fi
  echo "ok ${FUNCNAME[0]}"
}

seals_all_required_rows
key_set_mismatch_refused
test_value_refused_without_flag
optional_empty_key_allowed
missing_required_refused_unless_only
tracked_input_refused
input_outside_work_tree_refused
git_missing_refused
kustomization_resources_sorted
kustomization_other_indent_preserved
kustomization_unknown_shape_refused
rerun_rewrites_same_files
