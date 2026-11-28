#!/bin/sh
# Usage: log-export.sh <export|upload>   (CronJob log-export: initContainer `export` (logcli image), container `upload`
# (rclone image), sharing $LOG_EXPORT_DIR, default /export)
# export: every log line of the previous local day (Asia/Ho_Chi_Minh, UTC+7, no DST) from Loki (LOKI_ADDR) as gzipped
#         jsonl into $dir/<YYYY-MM-DD>.jsonl.gz, then the day into $dir/DAY (only after a complete export).
# upload: rclone copy of that file to <bucket>/logs/, then delete objects there older than LOG_EXPORT_KEEP (90d).
# S3 not configured (ops-secrets absent or S3_ENDPOINT/S3_BUCKET empty): BACKUP_REQUIRED=1 fails, otherwise skips, the
# same switch as pg-backup (backup.sh). Secrets are read from the environment only (never argv) and never printed.
# LOG_EXPORT_NOW (epoch seconds) overrides the clock in tests.
set -eu

mode="${1:-}"
dir="${LOG_EXPORT_DIR:-/export}"
S3_ENDPOINT="${S3_ENDPOINT:-}"
S3_BUCKET="${S3_BUCKET:-}"
S3_REGION="${S3_REGION:-us-east-1}"
keep="${LOG_EXPORT_KEEP:-90d}"

if [ -z "$S3_ENDPOINT" ] || [ -z "$S3_BUCKET" ]; then
  if [ "${BACKUP_REQUIRED:-0}" = 1 ]; then
    echo "log-export: S3 not configured" >&2
    exit 1
  fi
  echo "log-export: S3 not configured, skipping (BACKUP_REQUIRED=0)"
  exit 0
fi

case "$mode" in
  export)
    now="${LOG_EXPORT_NOW:-$(date -u +%s)}"
    # Local day = UTC date of (now + 7h); the previous one is 24h earlier.
    day=$(date -u -d "@$((now + 7 * 3600 - 86400))" +%Y-%m-%d)
    next=$(date -u -d "@$((now + 7 * 3600))" +%Y-%m-%d)
    out="$dir/$day.jsonl.gz"
    rm -f "$dir/DAY"
    # --limit=0: every line (logcli pages in --batch steps); all streams of the day, oldest first.
    logcli query --addr="${LOKI_ADDR:-http://lgtm.observability.svc.cluster.local:3100}" \
      --from="${day}T00:00:00+07:00" --to="${next}T00:00:00+07:00" --limit=0 --batch=5000 --forward \
      --output=jsonl --quiet '{service_name=~".+"}' >"$dir/$day.jsonl"
    # A plain file first, no pipe (POSIX sh has no pipefail): a failed query stops here under set -e.
    lines=$(wc -l <"$dir/$day.jsonl" | tr -d ' ')
    gzip -f "$dir/$day.jsonl"
    bytes=$(wc -c <"$out" | tr -d ' ')
    echo "$day" >"$dir/DAY"
    echo "log-export: exported $lines lines for $day ($bytes bytes gzipped)"
    ;;
  upload)
    day=$(cat "$dir/DAY")
    [ -n "$day" ] || { echo "log-export: empty DAY" >&2; exit 1; }
    remote=":s3,provider=Other,endpoint=\"$S3_ENDPOINT\",region=$S3_REGION,env_auth=false:$S3_BUCKET"
    RCLONE_S3_ACCESS_KEY_ID="${S3_ACCESS_KEY_ID:-}"
    RCLONE_S3_SECRET_ACCESS_KEY="${S3_SECRET_ACCESS_KEY:-}"
    export RCLONE_S3_ACCESS_KEY_ID RCLONE_S3_SECRET_ACCESS_KEY
    rclone copy "$dir/$day.jsonl.gz" "$remote/logs"
    rclone delete --min-age "$keep" "$remote/logs"
    echo "log-export: uploaded logs/$day.jsonl.gz"
    ;;
  *)
    echo "usage: log-export.sh <export|upload>" >&2
    exit 2
    ;;
esac
