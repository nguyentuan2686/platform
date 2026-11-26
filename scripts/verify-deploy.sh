#!/usr/bin/env bash
# Wait until Argo CD has deployed a Git revision. Used by the bootstrap CI job (infra/bootstrap) and by CD.
# Usage: scripts/verify-deploy.sh --app <name>[,<name>...] --revision <40-hex sha> [--deadline-s 900] [--no-smoke]
#                                 [--expect-outofsync <name>[,<name>...]]
# Deadline loop until every --app Application (namespace argocd) has status.sync.revision == <sha>,
# status.sync.status == Synced and status.health.status == Healthy, and every --expect-outofsync Application has
# status.sync.revision == <sha>, status.sync.status == OutOfSync and no ComparisonError condition (it renders at that
# revision but is not synced, e.g. prod-apps/prod-tunnel in bootstrap CI mode). Prints `OK argo-<app>` as each one is
# reached; on timeout `FAIL argo-<app>: sync=<..> health=<..> revision=<..> conditions=<..>` and exit 1. Bad arguments
# exit 2. --no-smoke skips the public smoke checks (added by Plan 4a Task 8; until then there is none to run).
# Needs kubectl (current context = the cluster) and jq. VERIFY_DEPLOY_INTERVAL_S sets the poll interval (default 10).
set -euo pipefail

usage() { sed -n '2,3p' "$0" >&2; exit 2; }

apps="" revision="" deadline_s=900 smoke=1 outofsync=""
while [ $# -gt 0 ]; do
  case "$1" in
    --app) apps="${2:-}"; shift 2 || usage ;;
    --revision) revision="${2:-}"; shift 2 || usage ;;
    --deadline-s) deadline_s="${2:-}"; shift 2 || usage ;;
    --no-smoke) smoke=0; shift ;;
    --expect-outofsync) outofsync="${2:-}"; shift 2 || usage ;;
    *) echo "verify-deploy: unknown argument $1" >&2; usage ;;
  esac
done
[ -n "$apps" ] || { echo "verify-deploy: --app is required" >&2; usage; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "verify-deploy: --revision must be a 40-hex commit sha" >&2; usage; }
[[ "$deadline_s" =~ ^[0-9]+$ ]] || { echo "verify-deploy: --deadline-s must be a number" >&2; usage; }
command -v jq >/dev/null 2>&1 || { echo "verify-deploy: jq is required" >&2; exit 2; }
interval="${VERIFY_DEPLOY_INTERVAL_S:-10}"

declare -A want=() last=()
pending=()
IFS=, read -ra list <<<"$apps"
for a in "${list[@]}"; do [ -n "$a" ] && { want[$a]=synced; pending+=("$a"); }; done
if [ -n "$outofsync" ]; then
  IFS=, read -ra list <<<"$outofsync"
  for a in "${list[@]}"; do [ -n "$a" ] && { want[$a]=outofsync; pending+=("$a"); }; done
fi

# state <app>: prints "sync health revision conditions" (conditions = type:message;... or -); 1 if not readable.
state() {
  local json
  json=$(kubectl -n argocd get application "$1" -o json 2>&1) || { echo "- - - $(head -c 200 <<<"$json" | tr '\n' ' ')"; return 1; }
  jq -r '[(.status.sync.status // "-"), (.status.health.status // "-"), (.status.sync.revision // "-"),
          ([(.status.conditions // [])[] | "\(.type):\(.message // "" | gsub("\\s+"; " ") | .[0:200])"] | join(";") | if . == "" then "-" else . end)]
         | join(" ")' <<<"$json"
}

reached() { # reached <app> <sync> <health> <revision> <conditions>
  [ "$4" = "$revision" ] || return 1
  if [ "${want[$1]}" = synced ]; then
    [ "$2" = Synced ] && [ "$3" = Healthy ]
  else
    [ "$2" = OutOfSync ] && [[ "$5" != *ComparisonError* ]]
  fi
}

end=$((SECONDS + deadline_s))
while :; do
  still=()
  for a in "${pending[@]}"; do
    s=$(state "$a") || true
    last[$a]="$s"
    read -r sync health rev conds <<<"$s"
    if reached "$a" "$sync" "$health" "$rev" "$conds"; then
      if [ "${want[$a]}" = synced ]; then echo "OK argo-$a"; else echo "OK argo-$a (OutOfSync, renders)"; fi
    else
      still+=("$a")
    fi
  done
  pending=("${still[@]+"${still[@]}"}")
  [ "${#pending[@]}" -eq 0 ] && break
  if [ "$SECONDS" -ge "$end" ]; then
    for a in "${pending[@]}"; do
      read -r sync health rev conds <<<"${last[$a]}"
      echo "FAIL argo-$a: sync=$sync health=$health revision=$rev conditions=$conds (want ${want[$a]} at $revision)"
    done
    exit 1
  fi
  sleep "$interval"
done

if [ "$smoke" = 1 ]; then
  echo "SKIP smoke: no public smoke checks yet (Plan 4a Task 8)"
fi
