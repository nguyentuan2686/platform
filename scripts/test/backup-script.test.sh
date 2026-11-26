#!/usr/bin/env bash
# Tests for deploy/k8s/platform/ops/{backup,heartbeat}.sh with stub pg_dump/rclone/curl first in PATH.
# Prints "ok <case>" per case; exits non-zero on the first failure. Offline, no Docker.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
backup="$root/deploy/k8s/platform/ops/backup.sh"
heartbeat="$root/deploy/k8s/platform/ops/heartbeat.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL $1: $2" >&2; exit 1; }

# Stubs record "<argv>" one call per line into $STUB_LOG/<name>.log; curl exits with $STUB_CURL_RC.
mkdir -p "$tmp/bin"
for tool in pg_dump rclone curl kubectl yq; do
  cat >"$tmp/bin/$tool" <<'STUB'
#!/bin/sh
name=$(basename "$0")
echo "$*" >>"$STUB_LOG/$name.log"
case "$name" in
  pg_dump)
    while [ $# -gt 0 ]; do
      if [ "$1" = -d ]; then db="$2"; fi
      if [ "$1" = -f ]; then : >"$2"; fi
      shift
    done
    [ "$db" = "${STUB_PG_DUMP_FAIL_DB:-}" ] && exit 1 ;;
  rclone) [ "$1" = "${STUB_RCLONE_FAIL_ON:-}" ] && exit 1 ;;
  curl) exit "${STUB_CURL_RC:-0}" ;;
  kubectl) [ "$3" = get ] && echo "Complete=True " ;;
esac
exit 0
STUB
  chmod +x "$tmp/bin/$tool"
done

ALL_DBS="orders notify reading_tuvi identity payment"

# run_case NAME CMD... : fresh log dir and backup dir; env set by the caller via `env`-style exports in a subshell.
setup() {
  rm -rf "$tmp/log" "$tmp/backup"
  mkdir -p "$tmp/log" "$tmp/backup"
}
# runner SCRIPT ARGS... (env from the calling subshell); rc in $rc, output in $tmp/out
runner() {
  rc=0
  PATH="$tmp/bin:$PATH" STUB_LOG="$tmp/log" BACKUP_DIR="$tmp/backup" BACKUP_DATABASES="$ALL_DBS" \
    sh "$@" >"$tmp/out" 2>&1 || rc=$?
}
calls() { if [ -f "$tmp/log/$1.log" ]; then wc -l <"$tmp/log/$1.log" | tr -d ' '; else echo 0; fi; }

S3_ENV=(S3_ENDPOINT=http://minio.example:9000 S3_BUCKET=pg-backups S3_REGION=us-east-1 S3_ACCESS_KEY_ID=test-ak-id S3_SECRET_ACCESS_KEY=test-sk-secret)

# restore_rc ARGS... -> rc and $tmp/out of scripts/pg-restore.sh with stub kubectl/yq
restore_rc() {
  rc=0
  PATH="$tmp/bin:$PATH" STUB_LOG="$tmp/log" bash "$root/scripts/pg-restore.sh" "$@" >"$tmp/out" 2>&1 || rc=$?
}
expect_refused() { # NAME ARGS...
  local name="$1"; shift
  setup
  restore_rc "$@"
  [ "$rc" -eq 1 ] || fail "$name" "expected exit 1, got $rc: $(cat "$tmp/out")"
  grep -q 'refusing\|must end in\|pass' "$tmp/out" || fail "$name" "no refusal message: $(cat "$tmp/out")"
  [ ! -f "$tmp/log/kubectl.log" ] || fail "$name" "kubectl was called for a refused target"
  echo "ok $name"
}
expect_accepted() { # NAME ARGS...
  local name="$1"; shift
  setup
  restore_rc "$@"
  [ "$rc" -eq 0 ] || fail "$name" "expected exit 0, got $rc: $(cat "$tmp/out")"
  grep -q '^pg-restore: ok' "$tmp/out" || fail "$name" "no ok line: $(cat "$tmp/out")"
  echo "ok $name"
}

restore_refuses_production_target() { expect_refused "${FUNCNAME[0]}" --db orders --target notify; }
restore_refuses_production_target_with_overwrite() { expect_refused "${FUNCNAME[0]}" --db orders --target notify --overwrite; }
restore_refuses_source_without_overwrite() { expect_refused "${FUNCNAME[0]}" --db orders --target orders; }
restore_refuses_postgres() { expect_refused "${FUNCNAME[0]}" --db orders --target postgres; }
restore_refuses_postgres_with_overwrite() { expect_refused "${FUNCNAME[0]}" --db orders --target postgres --overwrite; }
restore_refuses_template() { expect_refused "${FUNCNAME[0]}" --db orders --target template1 --overwrite; }
restore_refuses_missing_suffix() { expect_refused "${FUNCNAME[0]}" --db orders --target scratch; }
restore_accepts_suffix() { expect_accepted "${FUNCNAME[0]}" --db orders --target orders_restore_check; }
restore_accepts_default_target() { expect_accepted "${FUNCNAME[0]}" --db reading_tuvi; }
restore_accepts_overwrite_of_source() { expect_accepted "${FUNCNAME[0]}" --db orders --target orders --overwrite; }

dump_failure_exits_1_without_stamp() {
  setup
  env BACKUP_REQUIRED=1 STUB_PG_DUMP_FAIL_DB=reading_tuvi "${S3_ENV[@]}" bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' dump; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $(cat "$tmp/rc")"
  [ ! -e "$tmp/backup/STAMP" ] || fail "${FUNCNAME[0]}" "STAMP written after a failed dump"
  echo "ok ${FUNCNAME[0]}"
}

copy_failure_does_not_prune_or_ping() {
  prepare_upload
  env BACKUP_REQUIRED=1 STUB_RCLONE_FAIL_ON=copy "${S3_ENV[@]}" HEALTHCHECKS_BACKUP_URL=https://hc.example/ping/abc bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' upload; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -ne 0 ] || fail "${FUNCNAME[0]}" "expected non-zero exit"
  [ "$(calls rclone)" -eq 1 ] || fail "${FUNCNAME[0]}" "rclone called $(calls rclone) times (copy only expected)"
  [ "$(calls curl)" -eq 0 ] || fail "${FUNCNAME[0]}" "pinged after a failed copy"
  echo "ok ${FUNCNAME[0]}"
}
backup_required_without_s3_fails() {
  setup
  env BACKUP_REQUIRED=1 S3_ENDPOINT= S3_BUCKET= bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' dump; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 1 ] || fail "${FUNCNAME[0]}" "expected exit 1, got $(cat "$tmp/rc")"
  grep -q '^backup: S3 not configured$' "$tmp/out" || fail "${FUNCNAME[0]}" "message missing: $(cat "$tmp/out")"
  echo "ok ${FUNCNAME[0]}"
}

backup_optional_without_s3_skips() {
  setup
  env BACKUP_REQUIRED=0 S3_ENDPOINT= S3_BUCKET= bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' dump; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 0 ] || fail "${FUNCNAME[0]}" "expected exit 0, got $(cat "$tmp/rc"): $(cat "$tmp/out")"
  grep -q 'backup: S3 not configured, skipping (BACKUP_REQUIRED=0)' "$tmp/out" || fail "${FUNCNAME[0]}" "message missing: $(cat "$tmp/out")"
  [ "$(calls pg_dump)" -eq 0 ] || fail "${FUNCNAME[0]}" "pg_dump was called"
  echo "ok ${FUNCNAME[0]}"
}

dump_calls_pg_dump_per_db() {
  setup
  env BACKUP_REQUIRED=1 "${S3_ENV[@]}" bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' dump; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 0 ] || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out")"
  [ "$(calls pg_dump)" -eq 5 ] || fail "${FUNCNAME[0]}" "expected 5 pg_dump calls, got $(calls pg_dump)"
  local db
  for db in $ALL_DBS; do
    grep -q -- "^-Fc --no-owner --no-acl -d $db -f $tmp/backup/$db.dump\$" "$tmp/log/pg_dump.log" || fail "${FUNCNAME[0]}" "no call for $db: $(cat "$tmp/log/pg_dump.log")"
  done
  grep -Eq '^[0-9]{8}T[0-9]{6}Z$' "$tmp/backup/STAMP" || fail "${FUNCNAME[0]}" "bad STAMP: $(cat "$tmp/backup/STAMP")"
  echo "ok ${FUNCNAME[0]}"
}

# Prepared backup dir for the upload cases.
prepare_upload() {
  setup
  local db
  for db in $ALL_DBS; do : >"$tmp/backup/$db.dump"; done
  echo 20260102T030405Z >"$tmp/backup/STAMP"
}

upload_uses_stamp_prefix_and_prunes() {
  prepare_upload
  env BACKUP_REQUIRED=1 "${S3_ENV[@]}" bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' upload; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 0 ] || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out")"
  sed -n 1p "$tmp/log/rclone.log" | grep -q -- "^copy $tmp/backup :s3,provider=Other,endpoint=\"http://minio.example:9000\",region=us-east-1,env_auth=false:pg-backups/pg/20260102T030405Z" \
    || fail "${FUNCNAME[0]}" "copy argv wrong: $(cat "$tmp/log/rclone.log")"
  sed -n 2p "$tmp/log/rclone.log" | grep -q -- '^delete --min-age 14d :s3,.*:pg-backups/pg$' \
    || fail "${FUNCNAME[0]}" "prune argv wrong: $(cat "$tmp/log/rclone.log")"
  grep -q '^backup: uploaded 5 dumps to pg/20260102T030405Z$' "$tmp/out" || fail "${FUNCNAME[0]}" "summary missing: $(cat "$tmp/out")"
  ! grep -q -e test-ak-id -e test-sk-secret "$tmp/log/rclone.log" || fail "${FUNCNAME[0]}" "credentials on rclone argv"
  [ "$(calls curl)" -eq 0 ] || fail "${FUNCNAME[0]}" "curl pinged without HEALTHCHECKS_BACKUP_URL"
  echo "ok ${FUNCNAME[0]}"
}

upload_pings_backup_heartbeat_when_set() {
  prepare_upload
  env BACKUP_REQUIRED=1 "${S3_ENV[@]}" HEALTHCHECKS_BACKUP_URL=https://hc.example/ping/abc bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$backup' upload; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 0 ] || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out")"
  grep -q 'https://hc.example/ping/abc$' "$tmp/log/curl.log" || fail "${FUNCNAME[0]}" "no ping: $(cat "$tmp/log/curl.log" 2>&1)"
  grep -q 'hc.example' "$tmp/out" && fail "${FUNCNAME[0]}" "ping URL printed"
  echo "ok ${FUNCNAME[0]}"
}

heartbeat_unset_skips() {
  setup
  env HEALTHCHECKS_URL= bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$heartbeat'; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 0 ] || fail "${FUNCNAME[0]}" "expected exit 0: $(cat "$tmp/out")"
  grep -q '^heartbeat: HEALTHCHECKS_URL unset, skipping$' "$tmp/out" || fail "${FUNCNAME[0]}" "message missing: $(cat "$tmp/out")"
  [ "$(calls curl)" -eq 0 ] || fail "${FUNCNAME[0]}" "curl was called"
  echo "ok ${FUNCNAME[0]}"
}

heartbeat_failure_propagates() {
  setup
  env HEALTHCHECKS_URL=https://hc.example/ping/abc STUB_CURL_RC=22 bash -c "$(declare -f runner); tmp='$tmp'; ALL_DBS='$ALL_DBS'; rc=0; runner '$heartbeat'; echo \$rc >'$tmp/rc'"
  [ "$(cat "$tmp/rc")" -eq 22 ] || fail "${FUNCNAME[0]}" "expected exit 22, got $(cat "$tmp/rc")"
  grep -q -- '-fsS -m 10 --retry 3 https://hc.example/ping/abc' "$tmp/log/curl.log" || fail "${FUNCNAME[0]}" "curl argv: $(cat "$tmp/log/curl.log")"
  echo "ok ${FUNCNAME[0]}"
}

backup_required_without_s3_fails
backup_optional_without_s3_skips
dump_calls_pg_dump_per_db
upload_uses_stamp_prefix_and_prunes
upload_pings_backup_heartbeat_when_set
heartbeat_unset_skips
heartbeat_failure_propagates
dump_failure_exits_1_without_stamp
copy_failure_does_not_prune_or_ping
restore_refuses_production_target
restore_refuses_production_target_with_overwrite
restore_refuses_source_without_overwrite
restore_refuses_postgres
restore_refuses_postgres_with_overwrite
restore_refuses_template
restore_refuses_missing_suffix
restore_accepts_suffix
restore_accepts_default_target
restore_accepts_overwrite_of_source
