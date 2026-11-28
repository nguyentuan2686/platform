#!/usr/bin/env bash
# Hard check: a PR that changes a path the system docs describe must also change docs/system/.
# Usage: git diff --name-only <base>...<head> | PR_BODY=... PR_AUTHOR=... scripts/check-system-docs.sh
#   stdin: changed files, one per line.
#   PR_BODY: the PR description; a line "docs-system: n/a <reason>" opts out (the reason is required).
#   PR_AUTHOR: the PR author login; dependabot[bot] is exempt (version bumps only).
# Prints "NEEDS <file>" per watched file and exits 1 when docs/system/ is untouched; prints "OK system-docs" otherwise.
set -euo pipefail

# Paths the docs describe (extended regex on the repo-relative path).
watched='^(contracts/|deploy/k8s/|deploy/argocd/|infra/bootstrap/|\.github/workflows/|services/[^/]+/src/main/resources/db/migration/|services/[^/]+/migrations/|services/web/src/app/api/)'
# CD bumps image tags here; a tag change does not change any flow.
exempt='^deploy/k8s/overlays/prod/apps/kustomization\.yaml$'

if [ "${PR_AUTHOR:-}" = "dependabot[bot]" ]; then
  echo "OK system-docs (dependabot)"
  exit 0
fi

files="$(cat)"
needs=()
docs_changed=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if [[ "$f" == docs/system/* ]]; then docs_changed=1; continue; fi
  if [[ "$f" =~ $watched ]] && ! [[ "$f" =~ $exempt ]]; then needs+=("$f"); fi
done <<<"$files"

if [ "${#needs[@]}" -eq 0 ] || [ "$docs_changed" -eq 1 ]; then
  echo "OK system-docs"
  exit 0
fi

if grep -Eq '^docs-system: n/a[[:space:]]+[^[:space:]]' <<<"${PR_BODY:-}"; then
  echo "OK system-docs (opt-out in PR body)"
  exit 0
fi

for f in "${needs[@]}"; do echo "NEEDS $f"; done
echo "system-docs: these files change what docs/system/index.html describes. Update the page (and its Lịch sử entry)," >&2
echo "or add a line 'docs-system: n/a <reason>' to the PR description." >&2
exit 1
