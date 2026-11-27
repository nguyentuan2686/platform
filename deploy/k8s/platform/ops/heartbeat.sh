#!/bin/sh
# External dead-man's switch: GET HEALTHCHECKS_URL every 5 minutes. Unset (ops-secrets absent or key empty) = skip.
# The URL is a secret: it is never echoed.
set -u
url="${HEALTHCHECKS_URL:-}"
if [ -z "$url" ]; then
  echo "heartbeat: HEALTHCHECKS_URL unset, skipping"
  exit 0
fi
curl -fsS -m 10 --retry 3 "$url" >/dev/null
