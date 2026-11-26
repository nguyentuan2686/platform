#!/usr/bin/env bash
# Roll prod back to the last verified deploy after a failed verify-deploy (cd.yml job `verify-deploy`, exit 1 only).
# Usage: scripts/deploy-rollback.sh --sha <bump commit> [--repo .] [--push] [--verified-tag deploy-verified]
# The run whose bump is the NEWEST `deploy: ` commit on HEAD restores the last verified-good state: it reverts, as ONE
# commit, every not-yet-reverted `deploy: ` commit after the tag <verified-tag> (moved by scripts/deploy-mark-verified.sh
# on verify success) up to <sha>. Without that tag (first deploy) or when the tag is not an ancestor of <sha>, it
# reverts only <sha>. Every reverted commit must be a single-parent `deploy: ` commit authored by a GitHub App bot
# (`...[bot]@users.noreply.github.com`) that changes only files under deploy/k8s/overlays/; anything else is refused
# (exit 2, nothing changed). Other commits in the range (human changes, earlier reverts) are left alone.
# Message: `revert: deploy sha-<short> failed verify-deploy [skip ci]` (<short> = the image tag in <sha>'s subject);
# the body lists every reverted commit.
# A newer `deploy: ` commit after <sha> (on HEAD, or on origin/main when pushing) means a newer CD run owns the outcome:
# `ROLLBACK: superseded by newer deploy ...`, exit 4, nothing reverted (that run, if it also fails, reverts this bump
# too, because it reverts everything after the last verified deploy).
# --push: `git push origin HEAD:main`; a rejected push is rebased onto the new origin/main and retried (3 attempts).
# A revert or rebase conflict is aborted (worktree left clean), prints `ROLLBACK: conflict, manual action needed`
# and exits 3. Other failures exit 1. The caller sets the git identity (cd.yml: the CD App's bot user).
set -euo pipefail

usage() { sed -n '3p' "$0" >&2; exit 2; }
refuse() { echo "ROLLBACK: refused: $*" >&2; exit 2; }
conflict() { echo "ROLLBACK: conflict, manual action needed"; exit 3; }

sha="" repo=. push=0 vtag=deploy-verified
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) sha="${2:-}"; shift 2 || usage ;;
    --repo) repo="${2:-}"; shift 2 || usage ;;
    --push) push=1; shift ;;
    --verified-tag) vtag="${2:-}"; shift 2 || usage ;;
    *) echo "deploy-rollback: unknown argument $1" >&2; usage ;;
  esac
done
[[ "$sha" =~ ^[0-9a-f]{7,40}$ ]] || { echo "deploy-rollback: --sha must be a commit sha (hex)" >&2; usage; }
g() { git -C "$repo" "$@"; }

full="$(g rev-parse --verify -q "$sha^{commit}")" || refuse "$sha is not a commit in $repo"
[ -z "$(g status --porcelain --untracked-files=no)" ] || refuse "worktree of $repo has uncommitted changes"

is_deploy() { [[ "$(g log -1 --format=%s "$1")" == "deploy: "* ]]; }
# check_bump <commit>: refuses unless <commit> is a CD bump commit (the trust fence for every revert).
check_bump() {
  local c="$1" subject author changed f
  subject="$(g log -1 --format=%s "$c")"
  [[ "$subject" == "deploy: "* ]] || refuse "$c is not a CD bump commit (subject: $subject)"
  # Only the CD App writes bump commits (cd.yml commits as <slug>[bot]); a human `deploy: ...` commit is never reverted.
  author="$(g log -1 --format=%ae "$c")"
  [[ "$author" == *"[bot]@users.noreply.github.com" ]] || refuse "$c is not authored by the CD App (author: $author)"
  # A merge commit (more than one parent) is never a bump.
  [ "$(g rev-list --parents -n 1 "$c" | wc -w)" -eq 2 ] || refuse "$c is not a single-parent commit"
  changed="$(g diff-tree --no-commit-id --name-only -r "$c")"
  [ -n "$changed" ] || refuse "$c changes no files"
  while IFS= read -r f; do
    [[ "$f" == deploy/k8s/overlays/* ]] || refuse "$c changes $f, outside deploy/k8s/overlays/"
  done <<<"$changed"
}
check_bump "$full"
subject="$(g log -1 --format=%s "$full")"

tag="$(sed -nE 's/.* to (sha-[0-9a-f]{7,40})( \[skip ci\])?$/\1/p' <<<"$subject")"
[ -n "$tag" ] || tag="sha-${full:0:7}"
msg="revert: deploy $tag failed verify-deploy [skip ci]"

# newer_deploy <ref>: the first `deploy: ` commit after the bump on <ref>. A newer bump means a newer CD run deployed
# on top of this one and owns the outcome.
newer_deploy() {
  local log # captured first: an early-exiting reader on a pipe would SIGPIPE git log under pipefail
  log="$(g log --reverse --format='%h %s' "$full..$1")"
  awk '$2 == "deploy:" {print; exit}' <<<"$log"
}
superseded() {
  echo "ROLLBACK: superseded by newer deploy $1 on main; not reverting (the newest CD run restores the last verified deploy if it fails too)"
  exit 4
}
n="$(newer_deploy HEAD)"; [ -z "$n" ] || superseded "$n"

# Range to undo: the deploy commits after the last verified deploy, up to and including this bump.
base=""
if v="$(g rev-parse -q --verify "refs/tags/$vtag^{commit}")"; then
  if [ "$v" = "$full" ]; then
    refuse "$full is itself the last verified deploy ($vtag)"
  elif g merge-base --is-ancestor "$v" "$full"; then
    base="$v"
  else
    echo "ROLLBACK: $vtag (${v:0:7}) is not an ancestor of ${full:0:7}; reverting only this bump"
  fi
else
  echo "ROLLBACK: no $vtag tag yet (first verified deploy pending); reverting only this bump"
fi
targets=()
if [ -n "$base" ]; then
  range_log="$(g log --format=%H "$base..$full")" # newest first
  all_log="$(g log --format=%B "$base..HEAD")"
  while IFS= read -r c; do
    [ -n "$c" ] && is_deploy "$c" || continue
    # Already reverted by an earlier rollback (its body names every commit it reverts): skip.
    if grep -qF "This reverts commit $c" <<<"$all_log"; then echo "ROLLBACK: ${c:0:7} already reverted; skipped"; continue; fi
    check_bump "$c"
    targets+=("$c")
  done <<<"$range_log"
  echo "ROLLBACK: restoring the last verified deploy ${base:0:7} ($vtag)"
else
  targets=("$full")
fi

if ! g revert --no-commit "${targets[@]}" >/dev/null 2>&1; then
  g revert --abort >/dev/null 2>&1 || g reset -q --hard HEAD
  conflict
fi
body=""
for c in "${targets[@]}"; do body+="This reverts commit $c ($(g log -1 --format=%s "$c"))."$'\n'; done
g commit -q -m "$msg" -m "${body%$'\n'}"
echo "ROLLBACK: reverted ${#targets[@]} deploy commit(s) as $(g rev-parse --short=7 HEAD): $msg"
for c in "${targets[@]}"; do echo "ROLLBACK:   ${c:0:7} $(g log -1 --format=%s "$c")"; done

[ "$push" = 1 ] || exit 0
for attempt in 1 2 3; do
  if g push -q origin HEAD:main; then
    echo "ROLLBACK: pushed $(g rev-parse --short=7 HEAD) to origin main"
    exit 0
  fi
  [ "$attempt" -lt 3 ] || { echo "ROLLBACK: push to main failed 3 times" >&2; exit 1; }
  echo "ROLLBACK: push attempt $attempt rejected; rebasing onto the new origin/main"
  g fetch -q origin main
  n="$(newer_deploy origin/main)"; [ -z "$n" ] || superseded "$n"
  if ! g rebase -q origin/main >/dev/null 2>&1; then
    g rebase --abort >/dev/null 2>&1 || true # catch-ok: abort after a failed rebase; conflict() reports it
    conflict
  fi
  if [ "$(g rev-parse HEAD)" = "$(g rev-parse origin/main)" ]; then
    echo "ROLLBACK: ${full:0:7} is already reverted on origin main; nothing pushed"
    exit 0
  fi
done
