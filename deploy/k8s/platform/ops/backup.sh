#!/bin/sh
# Usage: backup.sh <dump|upload>   (CronJob pg-backup: initContainer `dump`, container `upload`)
# dump:   pg_dump -Fc of every database in BACKUP_DATABASES into $BACKUP_DIR (default /backup) and a STAMP file.
# upload: rclone copy $BACKUP_DIR to <bucket>/pg/<STAMP>, delete remote objects older than BACKUP_KEEP, then ping
#         HEALTHCHECKS_BACKUP_URL when set.
# S3 not configured (ops-secrets absent or S3_ENDPOINT/S3_BUCKET empty): BACKUP_REQUIRED=1 fails, otherwise skips
# (the upload container then reports platform.backup.skipped=1 to BACKUP_METRICS_URL: alert backup-skipped).
# Secrets are read from the environment only (never argv) and never printed.
set -eu

mode="${1:-}"
dir="${BACKUP_DIR:-/backup}"
S3_ENDPOINT="${S3_ENDPOINT:-}"
S3_BUCKET="${S3_BUCKET:-}"
S3_REGION="${S3_REGION:-us-east-1}"
keep="${BACKUP_KEEP:-14d}"
dbs="${BACKUP_DATABASES:-orders notify reading_tuvi identity payment}"

# report_skipped: pushes the OTLP gauge platform.backup.skipped=1 (job pg-backup) to BACKUP_METRICS_URL (the collector's
# /v1/metrics), which feeds the backup-skipped alert. A failed push is reported on stderr; the skip itself still exits 0.
report_skipped() {
  [ -n "${BACKUP_METRICS_URL:-}" ] || return 0
  body='{"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"pg-backup"}}]},"scopeMetrics":[{"scope":{"name":"backup.sh"},"metrics":[{"name":"platform.backup.skipped","description":"1 when the daily backup was skipped because S3 is not configured","gauge":{"dataPoints":[{"asInt":"1","timeUnixNano":"'"$(date +%s)"'000000000"}]}}]}]}]}'
  if command -v curl >/dev/null 2>&1; then
    printf '%s' "$body" | curl -fsS -m 10 -H 'Content-Type: application/json' --data-binary @- "$BACKUP_METRICS_URL" >/dev/null \
      || { echo "backup: could not report the skip to $BACKUP_METRICS_URL" >&2; return 0; }
  else
    wget -q -T 10 -O /dev/null --header 'Content-Type: application/json' --post-data "$body" "$BACKUP_METRICS_URL" \
      || { echo "backup: could not report the skip to $BACKUP_METRICS_URL" >&2; return 0; }
  fi
  echo "backup: reported the skip (platform.backup.skipped)"
}

if [ -z "$S3_ENDPOINT" ] || [ -z "$S3_BUCKET" ]; then
  if [ "${BACKUP_REQUIRED:-0}" = 1 ]; then
    echo "backup: S3 not configured" >&2
    exit 1
  fi
  echo "backup: S3 not configured, skipping (BACKUP_REQUIRED=0)"
  # Reported once per run, by the upload container (the dump init container skips first).
  [ "$mode" != upload ] || report_skipped
  exit 0
fi

# ping URL: curl where the image has it, busybox wget otherwise (the rclone image has no curl).
ping_url() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -m 10 --retry 3 "$1" >/dev/null
  else
    wget -q -T 10 -O /dev/null "$1"
  fi
}

case "$mode" in
  dump)
    umask 022 # the upload container runs as another uid and must read the dumps
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    for db in $dbs; do
      if ! pg_dump -Fc --no-owner --no-acl -d "$db" -f "$dir/$db.dump"; then
        echo "backup: pg_dump of $db failed" >&2
        exit 1
      fi
      echo "backup: dumped $db"
    done
    echo "$stamp" >"$dir/STAMP"
    ;;
  upload)
    stamp=$(cat "$dir/STAMP")
    [ -n "$stamp" ] || { echo "backup: empty STAMP" >&2; exit 1; }
    # The endpoint holds ':' so it must be quoted inside the rclone connection string.
    remote=":s3,provider=Other,endpoint=\"$S3_ENDPOINT\",region=$S3_REGION,env_auth=false:$S3_BUCKET"
    RCLONE_S3_ACCESS_KEY_ID="${S3_ACCESS_KEY_ID:-}"
    RCLONE_S3_SECRET_ACCESS_KEY="${S3_SECRET_ACCESS_KEY:-}"
    export RCLONE_S3_ACCESS_KEY_ID RCLONE_S3_SECRET_ACCESS_KEY
    rclone copy "$dir" "$remote/pg/$stamp" --exclude STAMP
    rclone delete --min-age "$keep" "$remote/pg"
    n=0
    for db in $dbs; do
      [ -f "$dir/$db.dump" ] && n=$((n + 1))
    done
    echo "backup: uploaded $n dumps to pg/$stamp"
    if [ -n "${HEALTHCHECKS_BACKUP_URL:-}" ]; then
      ping_url "$HEALTHCHECKS_BACKUP_URL"
      echo "backup: pinged backup heartbeat"
    fi
    ;;
  *)
    echo "usage: backup.sh <dump|upload>" >&2
    exit 2
    ;;
esac
