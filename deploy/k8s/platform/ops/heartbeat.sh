#!/bin/sh
# External dead-man's switch: GET HEALTHCHECKS_URL every 5 minutes. Unset (ops-secrets absent or key empty) = skip.
# The URL is a secret: it is never echoed. Every run prints one line: the heartbeat pods are the steady log source
# that the log-pipeline-down alert relies on (otel-collector filelog reads them).
set -u
url="${HEALTHCHECKS_URL:-}"
if [ -z "$url" ]; then
  echo "heartbeat: HEALTHCHECKS_URL unset, skipping"
  exit 0
fi
curl -fsS -m 10 --retry 3 "$url" >/dev/null || exit # curl's exit code
echo "heartbeat: ok"
