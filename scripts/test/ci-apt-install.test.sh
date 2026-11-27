#!/usr/bin/env bash
# Every workflow installs apt packages through scripts/ci-apt-install.sh, never with a bare `apt-get update`: the
# runner's preinstalled packages.microsoft.com source answered 403 twice and failed whole jobs
# (docs/lessons/2026-11-27-apt-microsoft-repo-403.md). Prints "ok <case>"; exits non-zero on the first failure.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

hits="$(grep -rn 'apt-get update' "$root/.github/workflows" || true)"
[ -z "$hits" ] || { echo "FAIL no-bare-apt-update: use scripts/ci-apt-install.sh instead:" >&2; echo "$hits" >&2; exit 1; }
echo "ok no-bare-apt-update"

out="$(bash "$root/scripts/ci-apt-install.sh" 2>&1)" && { echo "FAIL usage: ran with no packages" >&2; exit 1; }
grep -q 'usage:' <<<"$out" || { echo "FAIL usage: $out" >&2; exit 1; }
echo "ok usage"
