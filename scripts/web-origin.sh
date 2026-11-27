#!/usr/bin/env bash
# Usage: scripts/web-origin.sh [DOMAIN_YAML]   (default deploy/k8s/overlays/prod/domain/domain.yaml)
# Prints the web image's build-time WEB_ORIGIN: `https://<APP_DOMAIN>`, or nothing while APP_DOMAIN is a *.invalid
# placeholder (no public domain yet: the image keeps its localhost default). The same file feeds the runtime
# WEB_ORIGIN through the prod overlay's replacements (checked by scripts/k8s-validate.sh prod-domain), so the build
# arg and the runtime value cannot drift. ci.yml (job `changes`) passes the output to the web image build.
# Exits 1 when the file has no APP_DOMAIN or it is not a hostname.
set -euo pipefail
f="${1:-$(dirname "$0")/../deploy/k8s/overlays/prod/domain/domain.yaml}"
[ -f "$f" ] || { echo "web-origin: $f not found" >&2; exit 1; }
domain="$(sed -n 's/^[[:space:]]*APP_DOMAIN:[[:space:]]*"\{0,1\}\([^"[:space:]#]*\)"\{0,1\}.*$/\1/p' "$f" | tr -d '\r' | awk 'NR == 1')"
[[ "$domain" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || { echo "web-origin: APP_DOMAIN \"$domain\" in $f is missing or not a hostname" >&2; exit 1; }
case "$domain" in *.invalid) exit 0 ;; esac
echo "https://$domain"
