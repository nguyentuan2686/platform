#!/usr/bin/env bash
# Wait until Argo CD has deployed a Git revision, then smoke-test it through the cluster's ingress. Used by the
# bootstrap CI job (infra/bootstrap, --no-smoke) and by CD (.github/workflows/cd.yml job verify-deploy).
# Usage: scripts/verify-deploy.sh --app <name>[,<name>...] --revision <40-hex sha> [--deadline-s 900] [--no-smoke]
#                                 [--expect-outofsync <name>[,<name>...]] [--allow-descendant]
# Argo part: a deadline loop until, in one and the same poll, every --app Application (namespace argocd) has
# status.sync.revision == <sha>, status.sync.status == Synced and status.health.status == Healthy, and every
# --expect-outofsync Application has status.sync.revision == <sha>, status.sync.status == OutOfSync and no
# ComparisonError condition (it renders at that revision but is not synced, e.g. prod-apps/prod-tunnel in bootstrap CI
# mode). An app that was at the target once and then regresses therefore does not pass. --allow-descendant also accepts
# a revision that has <sha> as an ancestor (Argo tracks main, so a commit merged during the wait moves every app past
# the bump); ancestry is checked in VERIFY_DEPLOY_GIT_DIR (default: this repo), fetching origin main when unknown.
# Prints `OK argo-<app>` the first time each one is reached; on timeout
# `FAIL argo-<app>: sync=<..> health=<..> revision=<..> conditions=<..>` for each app not at the target in the last poll.
# Smoke part (skipped with --no-smoke): port-forwards kube-system/svc/traefik to 127.0.0.1:18080 (the firewall is
# closed and servicelb is off, so this is the internal ingress), waits for it to answer (`OK port-forward`), then runs
# `SMOKE_HOST_HEADER=<APP_DOMAIN> scripts/smoke-web.sh http://127.0.0.1:18080` until it passes (`OK smoke-web`) or the
# smoke deadline ends (`FAIL smoke-web: <reason>`). APP_DOMAIN is read from deploy/k8s/overlays/prod/domain/domain.yaml.
# Exit 0 when everything passed, 1 on a failed check, 2 on bad arguments. Needs kubectl (current context = the
# cluster), jq and curl. Env: VERIFY_DEPLOY_INTERVAL_S (poll interval, default 10), VERIFY_DEPLOY_PORT (18080),
# VERIFY_DEPLOY_PF_DEADLINE_S (60), VERIFY_DEPLOY_SMOKE_DEADLINE_S (180).
set -euo pipefail

usage() { sed -n '4,5p' "$0" >&2; exit 2; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apps="" revision="" deadline_s=900 smoke=1 outofsync="" allow_descendant=0
while [ $# -gt 0 ]; do
  case "$1" in
    --app) apps="${2:-}"; shift 2 || usage ;;
    --revision) revision="${2:-}"; shift 2 || usage ;;
    --deadline-s) deadline_s="${2:-}"; shift 2 || usage ;;
    --no-smoke) smoke=0; shift ;;
    --expect-outofsync) outofsync="${2:-}"; shift 2 || usage ;;
    --allow-descendant) allow_descendant=1; shift ;;
    *) echo "verify-deploy: unknown argument $1" >&2; usage ;;
  esac
done
[ -n "$apps" ] || { echo "verify-deploy: --app is required" >&2; usage; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "verify-deploy: --revision must be a 40-hex commit sha" >&2; usage; }
[[ "$deadline_s" =~ ^[0-9]+$ ]] || { echo "verify-deploy: --deadline-s must be a number" >&2; usage; }
command -v jq >/dev/null 2>&1 || { echo "verify-deploy: jq is required" >&2; exit 2; }
interval="${VERIFY_DEPLOY_INTERVAL_S:-10}"
git_dir="${VERIFY_DEPLOY_GIT_DIR:-$root}"
errf="$(mktemp)"
pf_pid="" pf_log=""
cleanup() {
  if [ -n "$pf_pid" ]; then
    kill "$pf_pid" 2>/dev/null || true # catch-ok: the port-forward may already have exited
    wait "$pf_pid" 2>/dev/null || true # catch-ok: its exit status after kill is not a check result
  fi
  rm -f "$errf" ${pf_log:+"$pf_log"}
}
trap cleanup EXIT

declare -A want=() last=() seen=() desc=()
order=()
IFS=, read -ra list <<<"$apps"
for a in "${list[@]}"; do [ -n "$a" ] && { want[$a]=synced; order+=("$a"); }; done
if [ -n "$outofsync" ]; then
  IFS=, read -ra list <<<"$outofsync"
  for a in "${list[@]}"; do [ -n "$a" ] && { want[$a]=outofsync; order+=("$a"); }; done
fi

# state <app>: prints "sync health revision conditions" (conditions = type:message;... or -); 1 if not readable.
state() {
  local json err out
  # stdout only: kubectl may warn on stderr (k3s's multi-call kubectl does), which is not JSON.
  json=$(kubectl -n argocd get application "$1" -o json 2>"$errf") || {
    err=$(tr '\n' ' ' <"$errf"); echo "- - - ${err:0:200}"; return 1; }
  out=$(jq -r '[(.status.sync.status // "-"), (.status.health.status // "-"), (.status.sync.revision // "-"),
          ([(.status.conditions // [])[] | "\(.type):\(.message // "" | gsub("\\s+"; " ") | .[0:200])"] | join(";") | if . == "" then "-" else . end)]
         | join(" ")' <<<"$json" 2>"$errf") || { echo "- - - unparseable kubectl output: ${json:0:120}"; return 1; }
  echo "$out"
}

# descendant <rev>: 0 when <rev> is a commit that has $revision as an ancestor (cached once decided).
descendant() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || return 1
  if [ -n "${desc[$1]:-}" ]; then [ "${desc[$1]}" = yes ]; return; fi
  if ! git -C "$git_dir" cat-file -e "$1^{commit}" 2>/dev/null; then
    # Not known yet (merged after the checkout): fetch main once per poll; still unknown means "not yet", not "no".
    git -C "$git_dir" fetch -q origin main 2>"$errf" || { echo "verify-deploy: git fetch: $(tr '\n' ' ' <"$errf")" >&2; return 1; }
    git -C "$git_dir" cat-file -e "$1^{commit}" 2>/dev/null || return 1
  fi
  if git -C "$git_dir" merge-base --is-ancestor "$revision" "$1" 2>/dev/null; then desc[$1]=yes; else desc[$1]=no; return 1; fi
}

note=""
reached() { # reached <app> <sync> <health> <revision> <conditions>; sets note for a descendant revision
  note=""
  if [ "$4" != "$revision" ]; then
    [ "$allow_descendant" = 1 ] && descendant "$4" || return 1
    note=" (at ${4:0:7}, descendant of ${revision:0:7})"
  fi
  if [ "${want[$1]}" = synced ]; then
    [ "$2" = Synced ] && [ "$3" = Healthy ]
  else
    [ "$2" = OutOfSync ] && [[ "$5" != *ComparisonError* ]]
  fi
}

end=$((SECONDS + deadline_s))
while :; do
  missing=()
  for a in "${order[@]}"; do
    s=$(state "$a") || true
    last[$a]="$s"
    read -r sync health rev conds <<<"$s"
    if reached "$a" "$sync" "$health" "$rev" "$conds"; then
      if [ -z "${seen[$a]:-}" ]; then
        seen[$a]=1
        if [ "${want[$a]}" = synced ]; then echo "OK argo-$a$note"; else echo "OK argo-$a (OutOfSync, renders)$note"; fi
      fi
    else
      missing+=("$a")
    fi
  done
  [ "${#missing[@]}" -eq 0 ] && break
  if [ "$SECONDS" -ge "$end" ]; then
    for a in "${missing[@]}"; do
      read -r sync health rev conds <<<"${last[$a]}"
      echo "FAIL argo-$a: sync=$sync health=$health revision=$rev conditions=$conds (want ${want[$a]} at $revision)"
    done
    exit 1
  fi
  sleep "$interval"
done

[ "$smoke" = 1 ] || exit 0

domain_file="$root/deploy/k8s/overlays/prod/domain/domain.yaml"
domain="$(awk '$1 == "APP_DOMAIN:" {gsub(/["\047]/, "", $2); print $2; exit}' "$domain_file" 2>/dev/null || true)"
[[ "$domain" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] \
  || { echo "FAIL smoke-web: no valid APP_DOMAIN in $domain_file (got '$domain')"; exit 1; }
port="${VERIFY_DEPLOY_PORT:-18080}"
url="http://127.0.0.1:$port"

pf_log="$(mktemp)"
kubectl -n kube-system port-forward svc/traefik "$port:80" >"$pf_log" 2>&1 &
pf_pid=$!
pf_end=$((SECONDS + ${VERIFY_DEPLOY_PF_DEADLINE_S:-60}))
while :; do
  if ! kill -0 "$pf_pid" 2>/dev/null; then
    echo "FAIL port-forward: kubectl port-forward exited: $(tail -n 3 "$pf_log" | tr '\n' ' ')"; exit 1
  fi
  c=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$url/" 2>/dev/null) || c=000
  [ "$c" != 000 ] && break
  if [ "$SECONDS" -ge "$pf_end" ]; then
    echo "FAIL port-forward: nothing answers on $url: $(tail -n 3 "$pf_log" | tr '\n' ' ')"; exit 1
  fi
  sleep 1
done
echo "OK port-forward (kube-system/svc/traefik -> $url)"

smoke_end=$((SECONDS + ${VERIFY_DEPLOY_SMOKE_DEADLINE_S:-180}))
while :; do
  if out=$(SMOKE_HOST_HEADER="$domain" bash "$root/scripts/smoke-web.sh" "$url" 2>&1); then
    echo "OK smoke-web"
    break
  fi
  if [ "$SECONDS" -ge "$smoke_end" ]; then
    echo "FAIL smoke-web: Host $domain via $url: $(tail -n 1 <<<"$out")"; exit 1
  fi
  sleep "$interval"
done
