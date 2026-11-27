#!/usr/bin/env bash
# Usage: scripts/e2e-backup.sh   (CI job k8s-e2e only, after the smoke and observability checks; needs kubectl + yq)
# End to end for the ops CronJobs on the kind stack with MinIO: quiesce the apps, record exact row counts, run the
# pg-backup CronJob once, restore every database into <db>_restore_check and compare counts, then run the heartbeat
# with and without HEALTHCHECKS_URL. Every wait is a deadline loop on Job conditions. Prints `OK <check>` /
# `FAIL <check>: <reason>`; exits non-zero on the first failure.
set -euo pipefail
cd "$(dirname "$0")/.."
DBS="orders notify reading_tuvi identity payment"
JOB_DEADLINE_S="${E2E_BACKUP_JOB_DEADLINE_S:-300}"
fail() { echo "FAIL $1: ${2:-}" >&2; exit 1; }

# wait_job NAME DEADLINE_S -> 0 on Complete (logs printed), 1 on Failed/timeout (logs printed)
wait_job() {
  local name="$1" end=$((SECONDS + $2)) conds
  while :; do
    conds=$(kubectl -n data get job "$name" -o jsonpath='{range .status.conditions[*]}{.type}={.status} {end}' 2>/dev/null || true)
    case "$conds" in
      *Complete=True*) kubectl -n data logs "job/$name" --all-containers --prefix=false || true; return 0 ;;
      *Failed=True*) break ;;
    esac
    [ "$SECONDS" -lt "$end" ] || break
    sleep 3
  done
  kubectl -n data logs "job/$name" --all-containers --prefix=false || true
  kubectl -n data describe job "$name" | tail -n 20 || true
  return 1
}

# counts DB -> "table=rows" lines for every table of schema public (exact count(*)), sorted
counts() {
  kubectl -n data exec postgres-0 -c postgres -- sh -c '
    set -e
    db="$1"
    for t in $(psql -U postgres -d "$db" -Atc "select tablename from pg_tables where schemaname = '"'"'public'"'"' order by 1"); do
      echo "$t=$(psql -U postgres -d "$db" -Atc "select count(*) from public.\"$t\"")"
    done' _ "$1"
}

# 1. quiesce: no app pod may write while counts are taken
kubectl -n apps scale deploy --all --replicas=0 >/dev/null
end=$((SECONDS + 180))
until [ -z "$(kubectl -n apps get pods -o name)" ]; do
  [ "$SECONDS" -lt "$end" ] || fail apps-quiesced "app pods still present: $(kubectl -n apps get pods -o name | paste -sd' ' -)"
  sleep 3
done
echo "OK apps-quiesced"

# 2. row counts of the source databases
declare -A before
for db in $DBS; do
  before[$db]=$(counts "$db")
  [ -n "${before[$db]}" ] || fail "backup-counts-$db" "no tables in schema public"
done
grep -Eq '^orders=[1-9][0-9]*$' <<<"${before[orders]}" || fail backup-orders-rows "orders table is empty: ${before[orders]}"
echo "OK backup-counts"

# 3. one backup run
kubectl -n data delete job pg-backup-e2e --ignore-not-found >/dev/null
kubectl -n data create job --from=cronjob/pg-backup pg-backup-e2e >/dev/null
wait_job pg-backup-e2e "$JOB_DEADLINE_S" || fail backup-job "pg-backup-e2e did not complete"
log=$(kubectl -n data logs job/pg-backup-e2e --all-containers --prefix=false)
grep -q 'backup: uploaded 5 dumps to pg/' <<<"$log" || fail backup-job "no 'uploaded 5 dumps' line: $log"
echo "OK backup-job"

# 4. restore into the scratch databases and compare
for db in $DBS; do
  bash scripts/pg-restore.sh --db "$db" --stamp latest || fail "backup-restore-$db" "pg-restore.sh failed"
  after=$(counts "${db}_restore_check")
  if [ "$after" != "${before[$db]}" ]; then
    fail "backup-restore-$db" "row counts differ: source [$(paste -sd' ' - <<<"${before[$db]}")] restored [$(paste -sd' ' - <<<"$after")]"
  fi
  echo "OK backup-restore-$db"
done

# 5. heartbeat with HEALTHCHECKS_URL (MinIO's liveness endpoint stands in for the external service)
kubectl -n data delete job hb-e2e hb-e2e-unset --ignore-not-found >/dev/null
kubectl -n data create job --from=cronjob/heartbeat hb-e2e >/dev/null
wait_job hb-e2e "$JOB_DEADLINE_S" || fail heartbeat "hb-e2e did not complete"
echo "OK heartbeat"

# 6. without the URL the heartbeat skips
kubectl -n data patch secret ops-secrets --type=json -p '[{"op":"remove","path":"/data/HEALTHCHECKS_URL"}]' >/dev/null
kubectl -n data create job --from=cronjob/heartbeat hb-e2e-unset >/dev/null
wait_job hb-e2e-unset "$JOB_DEADLINE_S" || fail heartbeat-unset "hb-e2e-unset did not complete"
hb_log=$(kubectl -n data logs job/hb-e2e-unset)
grep -q 'skipping' <<<"$hb_log" || fail heartbeat-unset "log has no 'skipping': $hb_log"
echo "OK heartbeat-unset"
echo "e2e-backup: ok"
