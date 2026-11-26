#!/usr/bin/env bash
# Usage: scripts/pg-restore.sh --db <name> [--stamp <STAMP>|latest] [--target <db>] [--overwrite] [--namespace data]
#                              [--deadline-s 600]
# Restores a pg_dump from S3-compatible storage (ops-secrets) with a one-off Job. The default target is the scratch
# database <db>_restore_check (dropped and recreated). Restoring over the source database needs --overwrite
# (pg_restore --clean --if-exists). Targets: <anything>_restore_check, or --db X --target X --overwrite; production and system databases are refused. Needs kubectl, yq (v4) and cluster access.
# Prints the Job logs; exit 0 only when the Job completed.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,3p' "$0" >&2; exit 2; }
db="" stamp=latest target="" overwrite=0 ns=data deadline=600
while [ $# -gt 0 ]; do
  case "$1" in
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --stamp) stamp="${2:?--stamp needs a value}"; shift 2 ;;
    --target) target="${2:?--target needs a value}"; shift 2 ;;
    --overwrite) overwrite=1; shift ;;
    --namespace) ns="${2:?--namespace needs a value}"; shift 2 ;;
    --deadline-s) deadline="${2:?--deadline-s needs a value}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$db" ] || usage

# Owner of each database (deploy/k8s/platform/postgres/init-db.sh); also the list of production databases.
owner_of() {
  case "$1" in
    orders) echo orders ;;
    notify) echo notify ;;
    reading_tuvi) echo reading ;;
    identity) echo identity ;;
    payment) echo payment ;;
    *) return 1 ;;
  esac
}
owner=$(owner_of "$db") || { echo "pg-restore: unknown database $db (orders notify reading_tuvi identity payment)" >&2; exit 2; }
[ -n "$target" ] || target="${db}_restore_check"
# Target safety: never the maintenance/template databases; a production database only as the explicit
# --overwrite of the very database being restored; otherwise a scratch name ending in _restore_check.
case "$target" in
  postgres | template*) echo "pg-restore: refusing target $target (system database)" >&2; exit 1 ;;
esac
if owner_of "$target" >/dev/null; then
  if [ "$overwrite" != 1 ] || [ "$target" != "$db" ]; then
    echo "pg-restore: refusing target $target (production database; only --db $target --overwrite may replace it)" >&2
    exit 1
  fi
elif [[ "$target" != *_restore_check ]]; then
  echo "pg-restore: target $target must end in _restore_check" >&2
  exit 1
fi
[[ "$stamp" =~ ^(latest|[0-9]{8}T[0-9]{6}Z)$ ]] || { echo "pg-restore: bad --stamp $stamp" >&2; exit 2; }
[[ "$target" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "pg-restore: bad --target $target" >&2; exit 2; }

job="pg-restore-${db//_/-}-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"
rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT
JOB="$job" NS="$ns" DB="$db" STAMP="$stamp" TARGET="$target" OWNER="$owner" OVERWRITE="$overwrite" \
  yq '
    .metadata.name = strenv(JOB) | .metadata.namespace = strenv(NS) |
    (.. | select(tag == "!!map" and has("name") and .name == "RESTORE_DB")).value = strenv(DB) |
    (.. | select(tag == "!!map" and has("name") and .name == "RESTORE_STAMP")).value = strenv(STAMP) |
    (.. | select(tag == "!!map" and has("name") and .name == "RESTORE_TARGET")).value = strenv(TARGET) |
    (.. | select(tag == "!!map" and has("name") and .name == "RESTORE_OWNER")).value = strenv(OWNER) |
    (.. | select(tag == "!!map" and has("name") and .name == "RESTORE_OVERWRITE")).value = strenv(OVERWRITE)
  ' scripts/templates/pg-restore-job.yaml >"$rendered"
kubectl apply -f "$rendered" >/dev/null
echo "pg-restore: job $job ($db@$stamp -> $target)"

end=$((SECONDS + deadline))
state=""
while :; do
  conds=$(kubectl -n "$ns" get job "$job" -o jsonpath='{range .status.conditions[*]}{.type}={.status} {end}' 2>/dev/null || true)
  case "$conds" in
    *Complete=True*) state=complete; break ;;
    *Failed=True*) state=failed; break ;;
  esac
  [ "$SECONDS" -lt "$end" ] || { state=timeout; break; }
  sleep 3
done

kubectl -n "$ns" logs "job/$job" --all-containers --prefix=false || true
case "$state" in
  complete) echo "pg-restore: ok ($db -> $target)" ;;
  failed) echo "pg-restore: FAILED job $job failed" >&2; exit 1 ;;
  *) echo "pg-restore: FAILED job $job not finished within ${deadline}s" >&2; exit 1 ;;
esac
