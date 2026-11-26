#!/usr/bin/env bash
# Tests for scripts/deploy-rollback.sh, offline in a temp repo: A (tags main) -> B (bump by the CD bot, touching only
# deploy/k8s/overlays/prod/apps/kustomization.yaml) -> C (unrelated later commit), plus a bare "origin" for --push.
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/scripts/deploy-rollback.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kpath=deploy/k8s/overlays/prod/apps/kustomization.yaml
src=0123456789abcdef0123456789abcdef01234567

fail() { echo "FAIL $1: $2" >&2; exit 1; }
g() { git -C "$repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }
bot() { git -C "$repo" -c user.name='cd-bot[bot]' -c user.email='4242+cd-bot[bot]@users.noreply.github.com' \
  -c commit.gpgsign=false "$@"; }

setup() { # sets repo, bump (sha of B)
  repo="$tmp/repo"
  rm -rf "$repo" "$tmp/origin.git"
  git init -q -b main "$repo"
  mkdir -p "$repo/$(dirname "$kpath")" "$repo/services/web"
  printf 'images:\n  - {name: ghcr.io/o/order-svc, newTag: main}\n  - {name: ghcr.io/o/web, newTag: main}\n' >"$repo/$kpath"
  echo one >"$repo/services/web/README.md"
  g add -A && g commit -q -m "feat: initial"
  sed -i "s|order-svc, newTag: main|order-svc, newTag: sha-$src|" "$repo/$kpath"
  bot commit -q -a -m "deploy: order-svc to sha-${src:0:7} [skip ci]" -m "BUMP order-svc main -> sha-${src:0:7}"
  bump="$(git -C "$repo" rev-parse HEAD)"
  echo two >"$repo/services/web/README.md"
  g commit -q -a -m "docs: later unrelated commit"
}

run() { # args...; sets rc, writes $tmp/out
  rc=0
  GIT_AUTHOR_NAME='cd-bot[bot]' GIT_AUTHOR_EMAIL='4242+cd-bot[bot]@users.noreply.github.com' \
    GIT_COMMITTER_NAME='cd-bot[bot]' GIT_COMMITTER_EMAIL='4242+cd-bot[bot]@users.noreply.github.com' \
    GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false \
    bash "$script" "$@" >"$tmp/out" 2>&1 || rc=$?
}

reverts_only_the_bump() {
  setup
  run --sha "$bump" --repo "$repo"
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q 'newTag: main}' <(grep order-svc "$repo/$kpath") || fail "${FUNCNAME[0]}" "order-svc tag not back to main: $(cat "$repo/$kpath")"
  [ "$(cat "$repo/services/web/README.md")" = two ] || fail "${FUNCNAME[0]}" "later commit not kept"
  [ "$(git -C "$repo" log -1 --format=%s)" = "revert: deploy sha-${src:0:7} failed verify-deploy [skip ci]" ] \
    || fail "${FUNCNAME[0]}" "subject: $(git -C "$repo" log -1 --format=%s)"
  [ "$(git -C "$repo" diff --name-only HEAD~1 HEAD)" = "$kpath" ] || fail "${FUNCNAME[0]}" "revert touched more than the kustomization"
  [ -z "$(git -C "$repo" status --porcelain)" ] || fail "${FUNCNAME[0]}" "worktree not clean"
  [ "$(git -C "$repo" rev-list --count HEAD)" = 4 ] || fail "${FUNCNAME[0]}" "expected exactly one new commit"
  echo "ok ${FUNCNAME[0]}"
}

refuses_commit_outside_overlays() {
  setup
  echo three >"$repo/services/web/README.md"
  sed -i "s|web, newTag: main|web, newTag: sha-$src|" "$repo/$kpath"
  bot commit -q -a -m "deploy: web to sha-${src:0:7} [skip ci]"
  local before; before="$(git -C "$repo" rev-parse HEAD)"
  run --sha "$before" --repo "$repo"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^ROLLBACK: refused' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  [ "$(git -C "$repo" rev-parse HEAD)" = "$before" ] || fail "${FUNCNAME[0]}" "HEAD moved"
  echo "ok ${FUNCNAME[0]}"
}

refuses_non_deploy_message() {
  setup
  sed -i "s|web, newTag: main|web, newTag: sha-$src|" "$repo/$kpath"
  g commit -q -a -m "fix: pin web by hand"
  local before; before="$(git -C "$repo" rev-parse HEAD)"
  run --sha "$before" --repo "$repo"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$repo" rev-parse HEAD)" = "$before" ] || fail "${FUNCNAME[0]}" "HEAD moved"
  run --sha main --repo "$repo"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "non-sha argument: rc=$rc"
  run --repo "$repo"
  [ "$rc" -eq 2 ] || fail "${FUNCNAME[0]}" "missing --sha: rc=$rc"
  echo "ok ${FUNCNAME[0]}"
}

conflict_aborts_cleanly() {
  setup
  sed -i "s|order-svc, newTag: sha-$src|order-svc, newTag: v9-by-hand|" "$repo/$kpath"
  g commit -q -a -m "fix: later edit of the same line"
  local before; before="$(git -C "$repo" rev-parse HEAD)"
  run --sha "$bump" --repo "$repo"
  [ "$rc" -eq 3 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx 'ROLLBACK: conflict, manual action needed' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  [ -z "$(git -C "$repo" status --porcelain)" ] || fail "${FUNCNAME[0]}" "worktree not clean: $(git -C "$repo" status --porcelain)"
  [ "$(git -C "$repo" rev-parse HEAD)" = "$before" ] || fail "${FUNCNAME[0]}" "HEAD moved"
  [ ! -e "$repo/.git/REVERT_HEAD" ] || fail "${FUNCNAME[0]}" "revert still in progress"
  echo "ok ${FUNCNAME[0]}"
}

push_lands_on_origin_main() {
  setup
  git init -q --bare "$tmp/origin.git"; git -C "$tmp/origin.git" symbolic-ref HEAD refs/heads/main
  g remote add origin "$tmp/origin.git"
  g push -q origin main
  run --sha "$bump" --repo "$repo" --push
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$tmp/origin.git" log -1 --format=%s main)" = "revert: deploy sha-${src:0:7} failed verify-deploy [skip ci]" ] \
    || fail "${FUNCNAME[0]}" "origin main: $(git -C "$tmp/origin.git" log -1 --format=%s main)"
  echo "ok ${FUNCNAME[0]}"
}

push_rebases_onto_a_moved_main() {
  setup
  git init -q --bare "$tmp/origin.git"; git -C "$tmp/origin.git" symbolic-ref HEAD refs/heads/main
  g remote add origin "$tmp/origin.git"
  g push -q origin main
  # Someone else lands a commit on origin main after our checkout.
  git clone -q "$tmp/origin.git" "$tmp/other"
  echo other >"$tmp/other/services/web/OTHER.md"
  git -C "$tmp/other" -c user.name=o -c user.email=o@example.invalid add -A
  git -C "$tmp/other" -c user.name=o -c user.email=o@example.invalid -c commit.gpgsign=false commit -q -m "feat: other"
  git -C "$tmp/other" push -q origin main
  rm -rf "$tmp/other"
  run --sha "$bump" --repo "$repo" --push
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$tmp/origin.git" log -1 --format=%s main)" = "revert: deploy sha-${src:0:7} failed verify-deploy [skip ci]" ] \
    || fail "${FUNCNAME[0]}" "origin main top: $(git -C "$tmp/origin.git" log -1 --format=%s main)"
  grep -qx "feat: other" <<<"$(git -C "$tmp/origin.git" log --format=%s main)" || fail "${FUNCNAME[0]}" "the other commit was lost"
  echo "ok ${FUNCNAME[0]}"
}

with_origin() { # bare origin with main = the setup repo
  git init -q --bare "$tmp/origin.git"; git -C "$tmp/origin.git" symbolic-ref HEAD refs/heads/main
  g remote add origin "$tmp/origin.git"
  g push -q origin main
}
on_origin() { # on_origin <bot|human> <message> <sed expr on kpath>: lands a commit on origin main from another clone
  rm -rf "$tmp/other"; git clone -q "$tmp/origin.git" "$tmp/other"
  local name=o email=o@example.invalid
  [ "$1" = bot ] && { name='cd-bot[bot]'; email='4242+cd-bot[bot]@users.noreply.github.com'; }
  sed -i "$3" "$tmp/other/$kpath"
  git -C "$tmp/other" -c user.name="$name" -c user.email="$email" -c commit.gpgsign=false commit -q -a -m "$2"
  git -C "$tmp/other" push -q origin main
  rm -rf "$tmp/other"
}

refuses_human_deploy_commit() {
  setup
  sed -i "s|web, newTag: main|web, newTag: sha-$src|" "$repo/$kpath"
  g commit -q -a -m "deploy: web to sha-${src:0:7} by hand"
  local before; before="$(git -C "$repo" rev-parse HEAD)"
  run --sha "$before" --repo "$repo"
  [ "$rc" -eq 2 ] && grep -q 'not authored by the CD App' "$tmp/out" || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$repo" rev-parse HEAD)" = "$before" ] || fail "${FUNCNAME[0]}" "HEAD moved"
  echo "ok ${FUNCNAME[0]}"
}

newer_deploy_supersedes() {
  # Review I1(a): a newer CD bump on main owns the outcome; reverting ours could undo good services.
  setup
  sed -i "s|web, newTag: main|web, newTag: sha-$src|" "$repo/$kpath"
  bot commit -q -a -m "deploy: web to sha-1234567 [skip ci]"
  local before; before="$(git -C "$repo" rev-parse HEAD)"
  run --sha "$bump" --repo "$repo"
  [ "$rc" -eq 4 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^ROLLBACK: superseded by newer deploy [0-9a-f]* deploy: web to sha-1234567' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  [ "$(git -C "$repo" rev-parse HEAD)" = "$before" ] || fail "${FUNCNAME[0]}" "HEAD moved"
  echo "ok ${FUNCNAME[0]}"
}

newer_deploy_on_origin_supersedes_push() {
  setup; with_origin
  on_origin bot "deploy: web to sha-7654321 [skip ci]" "s|web, newTag: main|web, newTag: sha-$src|"
  run --sha "$bump" --repo "$repo" --push
  [ "$rc" -eq 4 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$tmp/origin.git" log -1 --format=%s main)" = "deploy: web to sha-7654321 [skip ci]" ] \
    || fail "${FUNCNAME[0]}" "origin main changed: $(git -C "$tmp/origin.git" log -1 --format=%s main)"
  echo "ok ${FUNCNAME[0]}"
}

already_reverted_on_origin() {
  setup; with_origin
  on_origin human "revert: order-svc by hand" "s|order-svc, newTag: sha-$src|order-svc, newTag: main|"
  local top; top="$(git -C "$tmp/origin.git" rev-parse main)"
  run --sha "$bump" --repo "$repo" --push
  [ "$rc" -eq 0 ] && grep -q 'already reverted on origin main; nothing pushed' "$tmp/out" || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  [ "$(git -C "$tmp/origin.git" rev-parse main)" = "$top" ] || fail "${FUNCNAME[0]}" "origin main moved"
  echo "ok ${FUNCNAME[0]}"
}


reverts_only_the_bump
refuses_commit_outside_overlays
refuses_non_deploy_message
conflict_aborts_cleanly
push_lands_on_origin_main
push_rebases_onto_a_moved_main
refuses_human_deploy_commit
newer_deploy_supersedes
newer_deploy_on_origin_supersedes_push
already_reverted_on_origin
