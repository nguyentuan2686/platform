#!/usr/bin/env bash
# Tests for scripts/deploy-mark-verified.sh, offline in a temp repo with a bare "origin" for --push.
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/deploy-mark-verified.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kpath=deploy/k8s/overlays/prod/apps/kustomization.yaml

fail() { echo "FAIL $1: $2" >&2; exit 1; }
g() { git -C "$repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }

setup() { # sets repo; main = initial
  repo="$tmp/repo"
  rm -rf "$repo" "$tmp/origin.git" "$tmp/other"
  git init -q -b main "$repo"
  mkdir -p "$repo/$(dirname "$kpath")" "$repo/docs"
  printf 'images:\n  - {name: ghcr.io/o/web, newTag: main}\n' >"$repo/$kpath"
  g add -A && g commit -q -m "feat: initial"
}
deploy() { # deploy <tag7>: echoes the new commit
  sed -i "s|newTag: [^}]*|newTag: sha-$1|" "$repo/$kpath"
  g commit -q -a -m "deploy: web to sha-$1 [skip ci]"
  git -C "$repo" rev-parse HEAD
}
docs() { echo "$1" >"$repo/docs/x.md"; g add -A; g commit -q -m "docs: $1"; }
tag_at() { git -C "$1" rev-parse -q --verify refs/tags/deploy-verified 2>/dev/null || echo none; }
run() { rc=0; bash "$script" "$@" >"$tmp/out" 2>&1 || rc=$?; }

first_verify_creates_the_tag() {
  setup; local a; a="$(deploy aaaaaaa)"; docs later
  run --sha "$a" --repo "$repo"
  [ "$rc" -eq 0 ] && [ "$(tag_at "$repo")" = "$a" ] && grep -qx "VERIFIED: deploy-verified -> ${a:0:7}" "$tmp/out" \
    || fail "${FUNCNAME[0]}" "rc=$rc tag=$(tag_at "$repo"): $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

moves_forward_never_backwards() {
  setup; local a b
  a="$(deploy aaaaaaa)"; b="$(deploy bbbbbbb)"
  run --sha "$b" --repo "$repo"; [ "$(tag_at "$repo")" = "$b" ] || fail "${FUNCNAME[0]}" "B not recorded"
  run --sha "$a" --repo "$repo"
  [ "$rc" -eq 0 ] && [ "$(tag_at "$repo")" = "$b" ] || fail "${FUNCNAME[0]}" "moved back to A: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

not_recorded_when_overlays_changed_later() {
  # verify-deploy accepts a descendant: if a later bump or revert changed the overlays, what passed was not A's state.
  setup; local a; a="$(deploy aaaaaaa)"; deploy bbbbbbb >/dev/null
  run --sha "$a" --repo "$repo"
  [ "$rc" -eq 0 ] && [ "$(tag_at "$repo")" = none ] && grep -q 'deploy/k8s/overlays/ changed after' "$tmp/out" \
    || fail "${FUNCNAME[0]}" "rc=$rc tag=$(tag_at "$repo"): $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

push_creates_and_respects_a_newer_tag_on_origin() {
  setup; local a b
  a="$(deploy aaaaaaa)"; docs one
  git init -q --bare "$tmp/origin.git"; git -C "$tmp/origin.git" symbolic-ref HEAD refs/heads/main
  g remote add origin "$tmp/origin.git"; g push -q origin main
  run --sha "$a" --repo "$repo" --push
  [ "$rc" -eq 0 ] && [ "$(tag_at "$tmp/origin.git")" = "$a" ] || fail "${FUNCNAME[0]}" "create: rc=$rc: $(cat "$tmp/out")"
  # Another run lands B and records it on origin; this (stale) clone then tries to record A again: kept at B.
  git clone -q "$tmp/origin.git" "$tmp/other"
  sed -i "s|newTag: [^}]*|newTag: sha-bbbbbbb|" "$tmp/other/$kpath"
  git -C "$tmp/other" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false commit -q -a -m "deploy: web to sha-bbbbbbb"
  b="$(git -C "$tmp/other" rev-parse HEAD)"
  git -C "$tmp/other" push -q origin main "$b:refs/tags/deploy-verified" --force
  run --sha "$a" --repo "$repo" --push
  [ "$rc" -eq 0 ] && [ "$(tag_at "$tmp/origin.git")" = "$b" ] || fail "${FUNCNAME[0]}" "moved back: rc=$rc: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

bad_arguments_exit_2() {
  setup
  run --sha main --repo "$repo"; [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "non-hex: rc=$rc"
  run --sha 0123456 --repo "$repo"; [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "unknown commit: rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

first_verify_creates_the_tag
moves_forward_never_backwards
not_recorded_when_overlays_changed_later
push_creates_and_respects_a_newer_tag_on_origin
bad_arguments_exit_2
