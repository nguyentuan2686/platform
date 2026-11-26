#!/usr/bin/env bash
# Revert one CD bump commit after a failed verify-deploy (cd.yml job `verify-deploy`).
# Usage: scripts/deploy-rollback.sh --sha <bump commit> [--repo .] [--push]
# Refuses (exit 2) unless every file the commit changed is under deploy/k8s/overlays/ and its subject starts with
# `deploy: ` (only CD bump commits are reverted; CLAUDE.md: the CD bot pushes to main only under deploy/k8s/overlays/).
# Runs `git revert --no-edit <sha>` and rewrites the message to
# `revert: deploy sha-<short> failed verify-deploy [skip ci]` (<short> = the image tag in the bump subject).
# --push: `git push origin HEAD:main`; a rejected push is rebased onto the new origin/main and retried (3 attempts).
# A revert or rebase conflict is aborted (worktree left clean), prints `ROLLBACK: conflict, manual action needed`
# and exits 3. Other failures exit 1. The caller sets the git identity (cd.yml: the CD App's bot user).
set -euo pipefail

usage() { sed -n '3p' "$0" >&2; exit 2; }
refuse() { echo "ROLLBACK: refused: $*" >&2; exit 2; }
conflict() { echo "ROLLBACK: conflict, manual action needed"; exit 3; }

sha="" repo=. push=0
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) sha="${2:-}"; shift 2 || usage ;;
    --repo) repo="${2:-}"; shift 2 || usage ;;
    --push) push=1; shift ;;
    *) echo "deploy-rollback: unknown argument $1" >&2; usage ;;
  esac
done
[[ "$sha" =~ ^[0-9a-f]{7,40}$ ]] || { echo "deploy-rollback: --sha must be a commit sha (hex)" >&2; usage; }
g() { git -C "$repo" "$@"; }

full="$(g rev-parse --verify -q "$sha^{commit}")" || refuse "$sha is not a commit in $repo"
[ -z "$(g status --porcelain --untracked-files=no)" ] || refuse "worktree of $repo has uncommitted changes"

subject="$(g log -1 --format=%s "$full")"
[[ "$subject" == "deploy: "* ]] || refuse "$full is not a CD bump commit (subject: $subject)"
# A merge commit (more than one parent) is never a bump.
[ "$(g rev-list --parents -n 1 "$full" | wc -w)" -eq 2 ] || refuse "$full is not a single-parent commit"
changed="$(g diff-tree --no-commit-id --name-only -r "$full")"
[ -n "$changed" ] || refuse "$full changes no files"
while IFS= read -r f; do
  [[ "$f" == deploy/k8s/overlays/* ]] || refuse "$full changes $f, outside deploy/k8s/overlays/"
done <<<"$changed"

tag="$(sed -nE 's/.* to (sha-[0-9a-f]{7,40})( \[skip ci\])?$/\1/p' <<<"$subject")"
[ -n "$tag" ] || tag="sha-${full:0:7}"
msg="revert: deploy $tag failed verify-deploy [skip ci]"

if ! g revert --no-edit "$full" >/dev/null 2>&1; then
  g revert --abort >/dev/null 2>&1 || g reset -q --hard HEAD
  conflict
fi
g commit -q --amend -m "$msg" -m "This reverts commit $full ($subject)."
echo "ROLLBACK: reverted ${full:0:7} as $(g rev-parse --short=7 HEAD): $msg"

[ "$push" = 1 ] || exit 0
for attempt in 1 2 3; do
  if g push -q origin HEAD:main; then
    echo "ROLLBACK: pushed $(g rev-parse --short=7 HEAD) to origin main"
    exit 0
  fi
  [ "$attempt" -lt 3 ] || { echo "ROLLBACK: push to main failed 3 times" >&2; exit 1; }
  echo "ROLLBACK: push attempt $attempt rejected; rebasing onto the new origin/main"
  g fetch -q origin main
  if ! g rebase -q origin/main >/dev/null 2>&1; then
    g rebase --abort >/dev/null 2>&1 || true # catch-ok: abort after a failed rebase; conflict() reports it
    conflict
  fi
done
