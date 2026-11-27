#!/usr/bin/env bash
# Usage: scripts/ci-apt-install.sh <package>...   (GitHub-hosted Ubuntu runners only; needs passwordless sudo)
# Installs Ubuntu packages without depending on the third-party apt sources that GitHub preinstalls on its runner
# images (packages.microsoft.com): we install nothing from them, yet one 403 from them fails `apt-get update` and the
# whole job (docs/lessons/2026-11-27-apt-microsoft-repo-403.md, recurred in PR #34). Those source files are removed
# first; `apt-get update` then only talks to the Ubuntu mirrors and is retried once on a transient mirror error.
set -euo pipefail
[ "$#" -gt 0 ] || { echo "usage: $0 <package>..." >&2; exit 2; }

for f in /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
  [ -f "$f" ] || continue
  if grep -q 'packages\.microsoft\.com' "$f"; then
    echo "ci-apt-install: dropping unused apt source $f"
    sudo rm -f "$f"
  fi
done

if ! sudo apt-get update; then
  echo "ci-apt-install: apt-get update failed, retrying once in 15s" >&2
  sleep 15
  sudo apt-get update
fi
sudo apt-get install -y --no-install-recommends "$@"
