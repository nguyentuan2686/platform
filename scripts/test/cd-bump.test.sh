#!/usr/bin/env bash
# Tests for scripts/cd-bump.sh, offline: a temp git repo with linear commits A -> B -> C (plus an unrelated root U)
# and the fixture scripts/testdata/cd-bump/kustomization.yaml. Review Focus 1: a CD run must never move a tag to an
# older or unrelated commit, and a service that was not published keeps its tag.
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/cd-bump.sh"
fixture="$root/scripts/testdata/cd-bump/kustomization.yaml"
prod="$root/deploy/k8s/overlays/prod/apps/kustomization.yaml"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
prefix=ghcr.io/nguyentuan2686
all_svcs=(order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc web)

fail() { echo "FAIL $1: $2" >&2; exit 1; }

repo="$tmp/repo"
git init -q "$repo"
g() { git -C "$repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }
g commit -q --allow-empty -m A; A="$(g rev-parse HEAD)"
g commit -q --allow-empty -m B; B="$(g rev-parse HEAD)"
g commit -q --allow-empty -m C; C="$(g rev-parse HEAD)"
trunk="$(g symbolic-ref --short HEAD)"
g checkout -q --orphan other
g commit -q --allow-empty -m U; U="$(g rev-parse HEAD)"
g checkout -q "$trunk"

k="$tmp/kustomization.yaml"
fresh() { cp "$fixture" "$k"; }
set_tag() { # set_tag <svc> <tag>: edits the fixture copy without yq, so the test does not depend on the code under test
  sed -i "s|{name: $prefix/$1, newTag: [^}]*}|{name: $prefix/$1, newTag: $2}|" "$k"
  grep -q "{name: $prefix/$1, newTag: $2}" "$k" || fail set_tag "could not set $1 to $2"
}
tag_of() { sed -n "s|.*{name: $prefix/$1, newTag: \([^}]*\)}.*|\1|p" "$k"; }

run() { # run <args...>: sets rc, writes $tmp/out (stdout+stderr)
  rc=0
  bash "$script" --file "$k" --repo "$repo" "$@" >"$tmp/out" 2>&1 || rc=$?
}

main_tag_is_bumped() {
  fresh
  run --sha "$C" order-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(tag_of order-svc)" = "sha-$C" ] || fail "${FUNCNAME[0]}" "tag is $(tag_of order-svc)"
  grep -qx "BUMP order-svc main -> sha-${C:0:7}" "$tmp/out" || fail "${FUNCNAME[0]}" "no BUMP line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

ancestor_is_bumped() {
  fresh; set_tag web "sha-$A"
  run --sha "$C" web
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(tag_of web)" = "sha-$C" ] || fail "${FUNCNAME[0]}" "tag is $(tag_of web)"
  grep -qx "BUMP web sha-$A -> sha-${C:0:7}" "$tmp/out" || fail "${FUNCNAME[0]}" "no BUMP line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

older_sha_is_not_applied() { # Review Focus 1: CI for B finishes after CD already applied C
  fresh; set_tag order-svc "sha-$C"; cp "$k" "$tmp/before"
  run --sha "$B" order-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed: $(diff "$tmp/before" "$k" || true)"
  grep -qx "KEEP order-svc: current sha-$C is not an ancestor of $B" "$tmp/out" || fail "${FUNCNAME[0]}" "no KEEP line: $(cat "$tmp/out")"
  grep -qx "cd-bump: nothing to do" "$tmp/out" || fail "${FUNCNAME[0]}" "no nothing-to-do line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unrelated_sha_is_not_applied() {
  fresh; set_tag chart-svc "sha-$U"; cp "$k" "$tmp/before"
  run --sha "$C" chart-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed"
  grep -qx "KEEP chart-svc: current sha-$U is not an ancestor of $C" "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unknown_current_tag_is_kept() { # a hand-pinned tag (not main, not sha-<40 hex>) is never overwritten
  fresh; set_tag payment-svc "v1.2.3"; cp "$k" "$tmp/before"
  run --sha "$C" payment-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed"
  grep -q "^KEEP payment-svc: " "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

same_sha_is_noop() {
  fresh; set_tag identity-svc "sha-$C"; cp "$k" "$tmp/before"
  run --sha "$C" identity-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed"
  grep -qx "SAME identity-svc" "$tmp/out" || fail "${FUNCNAME[0]}" "no SAME line: $(cat "$tmp/out")"
  grep -qx "cd-bump: nothing to do" "$tmp/out" || fail "${FUNCNAME[0]}" "no nothing-to-do line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unpublished_service_keeps_tag() { # Review Focus 1: only order-svc was published for C; the other 6 stay byte for byte
  fresh; set_tag notify-svc "sha-$A"; cp "$k" "$tmp/before"
  run --sha "$C" order-svc
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(tag_of order-svc)" = "sha-$C" ] || fail "${FUNCNAME[0]}" "order-svc not bumped: $(cat "$tmp/out")"
  # Undo exactly that one line; the rest (the other 6 entries, comments, layout) must be identical byte for byte.
  sed "s|{name: $prefix/order-svc, newTag: sha-$C}|{name: $prefix/order-svc, newTag: main}|" "$k" >"$tmp/a.undone"
  cmp -s "$tmp/before" "$tmp/a.undone" || fail "${FUNCNAME[0]}" "more than order-svc changed: $(diff "$tmp/before" "$k" || true)"
  [ "$(grep -c '^BUMP ' "$tmp/out")" -eq 1 ] || fail "${FUNCNAME[0]}" "expected 1 BUMP line: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

either_order_converges() { # CD runs race: B published {order-svc, web}, C published {order-svc, chart-svc}
  fresh
  run --sha "$B" order-svc web; [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "B then C, B: rc=$rc: $(cat "$tmp/out")"
  run --sha "$C" order-svc chart-svc; [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "B then C, C: rc=$rc: $(cat "$tmp/out")"
  cp "$k" "$tmp/b_then_c"
  fresh
  run --sha "$C" order-svc chart-svc; [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "C then B, C: rc=$rc: $(cat "$tmp/out")"
  run --sha "$B" order-svc web; [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "C then B, B: rc=$rc: $(cat "$tmp/out")"
  grep -qx "KEEP order-svc: current sha-$C is not an ancestor of $B" "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  cmp -s "$k" "$tmp/b_then_c" || fail "${FUNCNAME[0]}" "orders differ: $(diff "$tmp/b_then_c" "$k" || true)"
  [ "$(tag_of order-svc)" = "sha-$C" ] && [ "$(tag_of web)" = "sha-$B" ] && [ "$(tag_of chart-svc)" = "sha-$C" ] \
    && [ "$(tag_of notify-svc)" = main ] || fail "${FUNCNAME[0]}" "not the newest tag per service: $(grep newTag "$k")"
  echo "ok ${FUNCNAME[0]}"
}

invalid_sha_exits_2() {
  local bad
  for bad in "${C:0:7}" "${C^^}" "${C}0" "sha-$C" "main" ""; do
    fresh; cp "$k" "$tmp/before"
    run --sha "$bad" order-svc
    [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "sha '$bad': expected exit 2, got $rc: $(cat "$tmp/out")"
    cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed for '$bad'"
  done
  fresh
  run --sha 0123456789abcdef0123456789abcdef01234567 order-svc # well-formed, not a commit in the repo
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "unknown commit: expected exit 2, got $rc: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

unknown_service_exits_2() {
  fresh; cp "$k" "$tmp/before"
  run --sha "$C" order-svc nope-svc
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "expected exit 2, got $rc: $(cat "$tmp/out")"
  cmp -s "$k" "$tmp/before" || fail "${FUNCNAME[0]}" "file changed (validation must run before any edit)"
  fresh
  run --sha "$C"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "no service: expected exit 2, got $rc"
  echo "ok ${FUNCNAME[0]}"
}

comments_preserved() {
  fresh; cp "$k" "$tmp/before"
  run --sha "$C" "${all_svcs[@]}"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep '#' "$tmp/before" >"$tmp/b.comments"
  grep '#' "$k" | sed "s|newTag: sha-$C|newTag: main|" >"$tmp/a.comments"
  cmp -s "$tmp/b.comments" "$tmp/a.comments" || fail "${FUNCNAME[0]}" "comments changed: $(diff "$tmp/b.comments" "$tmp/a.comments" || true)"
  # Same line count, entries in the same order, only the newTag values differ.
  sed "s|newTag: sha-$C|newTag: main|" "$k" >"$tmp/a.normalised"
  cmp -s "$tmp/before" "$tmp/a.normalised" || fail "${FUNCNAME[0]}" "layout changed: $(diff "$tmp/before" "$tmp/a.normalised" || true)"
  [ "$(grep -c '^BUMP ' "$tmp/out")" -eq 7 ] || fail "${FUNCNAME[0]}" "expected 7 BUMP lines: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

real_prod_file_changes_only_tags() { # the real file CD edits: a bump of all 7 changes only the 7 image lines
  cp "$prod" "$k"
  local s; for s in "${all_svcs[@]}"; do set_tag "$s" main; done # the committed tags may already be sha-<commit>
  cp "$k" "$tmp/before"
  run --sha "$C" "${all_svcs[@]}"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  sed "s|newTag: sha-$C|newTag: main|" "$k" >"$tmp/a.normalised"
  cmp -s "$tmp/before" "$tmp/a.normalised" || fail "${FUNCNAME[0]}" "more than the tags changed: $(diff "$tmp/before" "$tmp/a.normalised" || true)"
  echo "ok ${FUNCNAME[0]}"
}

main_tag_is_bumped
ancestor_is_bumped
older_sha_is_not_applied
unrelated_sha_is_not_applied
unknown_current_tag_is_kept
same_sha_is_noop
unpublished_service_keeps_tag
either_order_converges
invalid_sha_exits_2
unknown_service_exits_2
comments_preserved
real_prod_file_changes_only_tags
