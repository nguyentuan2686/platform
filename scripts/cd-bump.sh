#!/usr/bin/env bash
# CD: bump prod image tags in a kustomization `images:` list to sha-<commit>, never backwards.
# Usage: scripts/cd-bump.sh --file <kustomization> --sha <40-hex> [--repo <git dir>] <svc>...
#   <svc> is one of: order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc web.
# Per service, against the current newTag:
#   main, or sha-<X> with X an ancestor of <sha>  -> newTag: sha-<sha>, prints "BUMP <svc> <old> -> sha-<short>"
#   sha-<sha>                                     -> prints "SAME <svc>"
#   anything else (newer, unrelated, hand-pinned) -> prints "KEEP <svc>: ...", file untouched (Review Focus 1)
# Services not named are never touched. With no BUMP it prints "cd-bump: nothing to do".
# Exit 2: usage error, invalid sha, a sha that is not a commit in <repo>, unknown service. Exit 1: file/edit errors.
# Edits with `yq -i` (comments and order preserved). Run scripts/test/cd-bump.test.sh after changing this file.
set -euo pipefail

SERVICES=(order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc web)
IMAGE_PREFIX=ghcr.io/nguyentuan2686

usage() {
  echo "cd-bump: $1" >&2
  echo "usage: $0 --file <kustomization> --sha <40-hex> [--repo <git dir>] <svc>..." >&2
  exit 2
}

file="" sha="" repo="." have_sha=0
svcs=()
while [ $# -gt 0 ]; do
  case "$1" in
    --file | --sha | --repo)
      [ $# -ge 2 ] || usage "$1 needs a value"
      case "$1" in
        --file) file="$2" ;;
        --sha) sha="$2"; have_sha=1 ;;
        --repo) repo="$2" ;;
      esac
      shift 2
      ;;
    -*) usage "unknown option $1" ;;
    *) svcs+=("$1"); shift ;;
  esac
done

[ -n "$file" ] || usage "--file is required"
[ "$have_sha" -eq 1 ] || usage "--sha is required"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || usage "invalid sha '$sha' (need 40 lowercase hex)"
[ "${#svcs[@]}" -gt 0 ] || usage "no service given"
for s in "${svcs[@]}"; do
  case " ${SERVICES[*]} " in
    *" $s "*) ;;
    *) usage "unknown service '$s' (known: ${SERVICES[*]})" ;;
  esac
done
git -C "$repo" cat-file -e "${sha}^{commit}" 2>/dev/null || usage "$sha is not a commit in $repo"
[ -f "$file" ] || { echo "cd-bump: no such file $file" >&2; exit 1; }

short="${sha:0:7}"
bumped=0
for s in "${svcs[@]}"; do
  name="$IMAGE_PREFIX/$s"
  count="$(NAME="$name" yq '[.images[] | select(.name == strenv(NAME))] | length' "$file")"
  [ "$count" = 1 ] || { echo "cd-bump: $file has $count images entries for $name (need exactly 1)" >&2; exit 1; }
  cur="$(NAME="$name" yq '.images[] | select(.name == strenv(NAME)) | .newTag' "$file")"

  if [ "$cur" = "sha-$sha" ]; then
    echo "SAME $s"
    continue
  fi
  if [ "$cur" != main ]; then
    if [[ "$cur" =~ ^sha-([0-9a-f]{40})$ ]]; then
      # Not an ancestor (newer, unrelated, or unknown here): keep it, never move a tag backwards or sideways.
      if ! git -C "$repo" merge-base --is-ancestor "${BASH_REMATCH[1]}" "$sha" 2>/dev/null; then
        echo "KEEP $s: current $cur is not an ancestor of $sha"
        continue
      fi
    else
      echo "KEEP $s: current $cur is not main or sha-<40 hex>"
      continue
    fi
  fi

  NAME="$name" TAG="sha-$sha" yq -i '(.images[] | select(.name == strenv(NAME)) | .newTag) = strenv(TAG)' "$file"
  echo "BUMP $s $cur -> sha-$short"
  bumped=$((bumped + 1))
done

[ "$bumped" -gt 0 ] || echo "cd-bump: nothing to do"
