#!/usr/bin/env bash
# Hard check: every `uses:` is a local path (./...) or <owner>/<repo>[/<path>]@<40 lowercase hex>.
# Usage: scripts/check-action-pins.sh [file...]   (default: .github/workflows/*.yml, *.yaml and .github/**/action.yml)
# Prints `PIN: <file>:<line>: <value>` for each violation and exits 1; prints `OK action-pins` otherwise.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(
    { ls .github/workflows/*.yml .github/workflows/*.yaml 2>/dev/null || true; find .github -name action.yml -o -name action.yaml 2>/dev/null; } | sort -u
  )
fi

bad=0
for f in "${files[@]}"; do
  n=0
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    # Match `uses:` as a YAML key (optionally after `- `), skipping commented lines.
    if [[ "$line" =~ ^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*(.*)$ ]]; then
      value="${BASH_REMATCH[2]}"
      value="${value%%[[:space:]]#*}"            # strip trailing comment
      value="${value%"${value##*[![:space:]]}"}" # trim trailing whitespace
      value="${value#[\"\']}"
      value="${value%[\"\']}"
      if [[ "$value" == ./* ]]; then continue; fi
      if [[ "$value" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}$ ]]; then continue; fi
      echo "PIN: $f:$n: $value"
      bad=1
    fi
  done <"$f"
done

if [ "$bad" -ne 0 ]; then
  echo "FAIL action-pins: pin every action to a 40-hex commit SHA with a '# vX.Y.Z' comment" >&2
  exit 1
fi
echo "OK action-pins"
