#!/usr/bin/env bash
# Tests for deploy/k8s/platform/ops/log-export.sh with stub logcli/rclone first in PATH.
# Prints "ok <case>" per case; exits non-zero on the first failure. Offline, no Docker.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/deploy/k8s/platform/ops/log-export.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

# Stubs record their argv (one call per line) into $tmp/log/<name>.log. logcli prints two jsonl lines (or fails with
# STUB_LOGCLI_RC); rclone fails on the subcommand named by STUB_RCLONE_FAIL_ON.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/logcli" <<'STUB'
#!/bin/sh
echo "$*" >>"$STUB_LOG/logcli.log"
[ "${STUB_LOGCLI_RC:-0}" = 0 ] || exit "$STUB_LOGCLI_RC"
echo '{"labels":{"service_name":"web"},"line":"a"}'
echo '{"labels":{"service_name":"web"},"line":"b"}'
STUB
cat >"$tmp/bin/rclone" <<'STUB'
#!/bin/sh
echo "$*" >>"$STUB_LOG/rclone.log"
[ "$1" = "${STUB_RCLONE_FAIL_ON:-}" ] && exit 1
exit 0
STUB
chmod +x "$tmp/bin/"*

S3_ENV=(S3_ENDPOINT=http://minio.example:9000 S3_BUCKET=pg-backups S3_ACCESS_KEY_ID=test-ak-id S3_SECRET_ACCESS_KEY=test-sk-secret)
# 2026-11-28T00:30:00+07:00 = 2026-11-27T17:30:00Z: the 00:30 run exports 2026-11-27 (local day).
NOW=1795800600

# run MODE [ENV...] -> exit code in $tmp/rc, combined output in $tmp/out
run() {
  local mode="$1"; shift
  rm -rf "$tmp/log" "$tmp/out" "$tmp/export"; mkdir -p "$tmp/log" "$tmp/export"
  local rc=0
  env -i PATH="$tmp/bin:/usr/bin:/bin" STUB_LOG="$tmp/log" LOG_EXPORT_DIR="$tmp/export" LOG_EXPORT_NOW="$NOW" "$@" \
    sh "$script" "$mode" >"$tmp/out" 2>&1 || rc=$?
  echo "$rc" >"$tmp/rc"
}
rc() { cat "$tmp/rc"; }

skips_without_s3() {
  run export BACKUP_REQUIRED=0
  [ "$(rc)" -eq 0 ] || fail "${FUNCNAME[0]}" "exit $(rc): $(cat "$tmp/out")"
  grep -q '^log-export: S3 not configured, skipping' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  [ ! -e "$tmp/log/logcli.log" ] || fail "${FUNCNAME[0]}" "logcli ran without S3"
  echo "ok ${FUNCNAME[0]}"
}

fails_without_s3_when_required() {
  run upload BACKUP_REQUIRED=1
  [ "$(rc)" -eq 1 ] || fail "${FUNCNAME[0]}" "exit $(rc): $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

exports_the_previous_local_day_gzipped() {
  run export "${S3_ENV[@]}" LOKI_ADDR=http://lgtm.example:3100
  [ "$(rc)" -eq 0 ] || fail "${FUNCNAME[0]}" "exit $(rc): $(cat "$tmp/out")"
  local argv; argv=$(cat "$tmp/log/logcli.log")
  grep -q -- '--addr=http://lgtm.example:3100' <<<"$argv" || fail "${FUNCNAME[0]}" "addr: $argv"
  grep -q -- '--from=2026-11-27T00:00:00+07:00 --to=2026-11-28T00:00:00+07:00' <<<"$argv" || fail "${FUNCNAME[0]}" "range: $argv"
  grep -q -- '--limit=0' <<<"$argv" && grep -q -- '--output=jsonl' <<<"$argv" || fail "${FUNCNAME[0]}" "flags: $argv"
  [ "$(cat "$tmp/export/DAY")" = 2026-11-27 ] || fail "${FUNCNAME[0]}" "DAY file"
  [ "$(gzip -dc "$tmp/export/2026-11-27.jsonl.gz" | wc -l | tr -d ' ')" = 2 ] || fail "${FUNCNAME[0]}" "gzip content"
  grep -q '^log-export: exported 2 lines for 2026-11-27' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

a_failed_query_fails_and_leaves_no_file() {
  run export "${S3_ENV[@]}" STUB_LOGCLI_RC=3
  [ "$(rc)" -ne 0 ] || fail "${FUNCNAME[0]}" "exit 0 although logcli failed"
  [ ! -e "$tmp/export/DAY" ] || fail "${FUNCNAME[0]}" "DAY written after a failed export"
  echo "ok ${FUNCNAME[0]}"
}

uploads_under_logs_and_prunes_without_credentials_on_argv() {
  run upload "${S3_ENV[@]}"
  # upload needs the export's files: prepare them, then run again in the same dir
  echo 2026-11-27 >"$tmp/export/DAY"; : | gzip >"$tmp/export/2026-11-27.jsonl.gz"
  local rc=0
  env -i PATH="$tmp/bin:/usr/bin:/bin" STUB_LOG="$tmp/log" LOG_EXPORT_DIR="$tmp/export" "${S3_ENV[@]}" \
    sh "$script" upload >"$tmp/out" 2>&1 || rc=$?
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "exit $rc: $(cat "$tmp/out")"
  grep -q -- "^copy $tmp/export/2026-11-27.jsonl.gz :s3,provider=Other,endpoint=\"http://minio.example:9000\",region=us-east-1,env_auth=false:pg-backups/logs$" "$tmp/log/rclone.log" \
    || fail "${FUNCNAME[0]}" "copy argv: $(cat "$tmp/log/rclone.log")"
  grep -q -- '^delete --min-age 90d :s3,.*:pg-backups/logs$' "$tmp/log/rclone.log" || fail "${FUNCNAME[0]}" "prune: $(cat "$tmp/log/rclone.log")"
  ! grep -q -e test-ak-id -e test-sk-secret "$tmp/log/rclone.log" || fail "${FUNCNAME[0]}" "credentials on argv"
  grep -q '^log-export: uploaded logs/2026-11-27.jsonl.gz' "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

a_failed_copy_does_not_prune() {
  run upload "${S3_ENV[@]}"
  echo 2026-11-27 >"$tmp/export/DAY"; : | gzip >"$tmp/export/2026-11-27.jsonl.gz"
  local rc=0
  env -i PATH="$tmp/bin:/usr/bin:/bin" STUB_LOG="$tmp/log" LOG_EXPORT_DIR="$tmp/export" STUB_RCLONE_FAIL_ON=copy "${S3_ENV[@]}" \
    sh "$script" upload >"$tmp/out" 2>&1 || rc=$?
  [ "$rc" -ne 0 ] || fail "${FUNCNAME[0]}" "exit 0 although copy failed"
  ! grep -q '^delete' "$tmp/log/rclone.log" || fail "${FUNCNAME[0]}" "pruned after a failed copy"
  echo "ok ${FUNCNAME[0]}"
}

skips_without_s3
fails_without_s3_when_required
exports_the_previous_local_day_gzipped
a_failed_query_fails_and_leaves_no_file
uploads_under_logs_and_prunes_without_credentials_on_argv
a_failed_copy_does_not_prune
