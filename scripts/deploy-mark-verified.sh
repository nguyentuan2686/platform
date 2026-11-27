#!/usr/bin/env bash
# Record a CD bump commit as the last verified-good deploy (cd.yml job `verify-deploy`, after verify-deploy exit 0).
# Usage: scripts/deploy-mark-verified.sh --sha <bump commit> [--repo .] [--push] [--tag deploy-verified]
# Moves the lightweight tag <tag> to <sha> (scripts/deploy-rollback.sh rolls back to it), but only when:
#   - <sha> is on HEAD (the main checkout) and no commit after it on HEAD changes deploy/k8s/overlays/, so the overlay
#     state that was verified is exactly <sha>'s (verify-deploy accepts a descendant revision, so a later revert or
#     bump could be what Argo CD actually showed; then nothing is recorded and the newer run records its own);
#   - the tag does not exist yet, or its commit is an ancestor of <sha> (never moves backwards).
# Otherwise prints `VERIFIED: kept <tag> ...` and exits 0. --push: `git push --force-with-lease` of the tag to origin
# (lease on the tag's old value, so two runs racing cannot move it backwards); a lost race is re-evaluated (3 attempts).
# Prints `VERIFIED: <tag> -> <short>` when it moved. Bad arguments exit 2, other failures 1.
set -euo pipefail

usage() { sed -n '3p' "$0" >&2; exit 2; }
sha="" repo=. push=0 tag=deploy-verified
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) sha="${2:-}"; shift 2 || usage ;;
    --repo) repo="${2:-}"; shift 2 || usage ;;
    --push) push=1; shift ;;
    --tag) tag="${2:-}"; shift 2 || usage ;;
    *) echo "deploy-mark-verified: unknown argument $1" >&2; usage ;;
  esac
done
[[ "$sha" =~ ^[0-9a-f]{7,40}$ ]] || { echo "deploy-mark-verified: --sha must be a commit sha (hex)" >&2; usage; }
g() { git -C "$repo" "$@"; }
full="$(g rev-parse --verify -q "$sha^{commit}")" || { echo "deploy-mark-verified: $sha is not a commit" >&2; exit 2; }

# decide <head ref>: prints the old tag commit (or "none") when the tag should move to $full; returns 1 to keep it.
decide() {
  g merge-base --is-ancestor "$full" "$1" || { echo "VERIFIED: kept $tag: ${full:0:7} is not on main" >&2; return 1; }
  if ! g diff --quiet "$full" "$1" -- deploy/k8s/overlays/; then
    echo "VERIFIED: kept $tag: deploy/k8s/overlays/ changed after ${full:0:7} on main (a newer run records its own)" >&2
    return 1
  fi
  local old
  if old="$(g rev-parse -q --verify "refs/tags/$tag^{commit}")"; then
    [ "$old" != "$full" ] || { echo "VERIFIED: kept $tag: already at ${full:0:7}" >&2; return 1; }
    g merge-base --is-ancestor "$old" "$full" \
      || { echo "VERIFIED: kept $tag at ${old:0:7}: not an ancestor of ${full:0:7} (never backwards)" >&2; return 1; }
    echo "$old"
  else
    echo none
  fi
}

if [ "$push" = 0 ]; then
  if ! out="$(decide HEAD 2>&1)"; then echo "$out"; exit 0; fi
  g tag -f "$tag" "$full" >/dev/null
  echo "VERIFIED: $tag -> ${full:0:7}"
  exit 0
fi

for attempt in 1 2 3; do
  g fetch -q --force origin main "+refs/tags/$tag:refs/tags/$tag" 2>/dev/null || g fetch -q origin main
  if ! old="$(decide origin/main 2>&1)"; then echo "$old"; exit 0; fi
  lease="$old"; [ "$lease" != none ] || lease=""
  if g push -q --force-with-lease="refs/tags/$tag:$lease" origin "$full:refs/tags/$tag"; then
    echo "VERIFIED: $tag -> ${full:0:7}"
    exit 0
  fi
  [ "$attempt" -lt 3 ] || { echo "VERIFIED: pushing $tag failed 3 times" >&2; exit 1; }
  echo "VERIFIED: push of $tag rejected (attempt $attempt); re-evaluating"
done
