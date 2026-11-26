#!/usr/bin/env bash
# Usage: scripts/k8s-e2e.sh   (CI job k8s-e2e only; never run kind or this stack on the dev machine)
# Assumes a current kube context (kind) with the <svc>:e2e images already loaded. Deploys the e2e overlay, waits for
# the stack, port-forwards it to the fixed local ports the smoke scripts default to, then runs every smoke script and
# the observability checks. The first failure exits non-zero; a trap stops the port-forwards.
# Port-forward logs go to E2E_LOG_DIR (default: a temp dir), which the CI diagnostics step uploads.
set -euo pipefail
cd "$(dirname "$0")/.."
LOG_DIR="${E2E_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"
fail() { echo "FAIL: $*" >&2; exit 1; }

# --- 1. apply (a first apply can race CRD registration: retry once on "no matches for kind")
if ! out=$(kubectl apply -k deploy/k8s/overlays/e2e 2>&1); then
  echo "$out"
  printf '%s' "$out" | grep -q 'no matches for kind' || fail "kubectl apply failed"
  echo "retrying apply once (no matches for kind)"
  kubectl apply -k deploy/k8s/overlays/e2e
else
  echo "$out"
fi

# --- 2. wait for data, observability, then apps
# (rollout status takes one resource per call)
for r in statefulset/postgres statefulset/redpanda deploy/mailpit deploy/minio; do kubectl -n data rollout status "$r" --timeout=300s; done
for r in deploy/lgtm ds/otel-collector; do kubectl -n observability rollout status "$r" --timeout=300s; done
kubectl -n apps wait --for=condition=Available deploy --all --timeout=600s

# --- 3. port-forwards. Each runs in a restart loop (a pod restart or a dropped stream ends kubectl port-forward).
PF_PIDS=()
stop_port_forwards() {
  local pid kids
  for pid in "${PF_PIDS[@]}"; do
    kids=$(pgrep -P "$pid" || true)
    kill "$pid" 2>/dev/null || true
    # shellcheck disable=SC2086 # one word per child pid
    [ -z "$kids" ] || kill $kids 2>/dev/null || true
  done
}
trap stop_port_forwards EXIT

# pf NAMESPACE TARGET PORTS...
pf() {
  local ns="$1" target="$2"; shift 2
  local log="$LOG_DIR/port-forward-${target#*/}.log"
  (
    while :; do
      kubectl -n "$ns" port-forward "$target" "$@" >>"$log" 2>&1 || true
      echo "port-forward $target exited; restarting" >>"$log"
      sleep 1
    done
  ) &
  PF_PIDS+=("$!")
}

# wait_url URL [DEADLINE_S] -> until curl -fsS answers
wait_url() {
  local url="$1" deadline=$((SECONDS + ${2:-180})) last=""
  until last=$(curl -fsS --max-time 5 -o /dev/null "$url" 2>&1); do
    [ "$SECONDS" -lt "$deadline" ] || fail "$url did not answer within ${2:-180}s: $last"
    sleep 2
  done
  echo "up: $url"
}

pf apps svc/order-svc 8080:8080
pf apps svc/notify-svc 8081:8081
pf apps svc/chart-svc 8082:8082
pf apps svc/reading-tuvi 8083:8083
pf apps svc/identity-svc 8084:8084
pf apps svc/payment-svc 8085:8085
pf apps svc/web 3001:3000
pf data svc/mailpit 8025:8025
pf observability svc/lgtm 3000:3000 9090:9090 3100:3100 3200:3200

for port in 8080 8081 8082 8083 8084 8085 3001; do wait_url "http://localhost:$port/health/ready"; done
wait_url http://localhost:8025/readyz
wait_url http://localhost:3000/api/health
wait_url http://localhost:9090/-/ready
wait_url http://localhost:3100/ready
wait_url http://localhost:3200/ready

# --- 4. smoke scripts, then the observability checks (their trace/log search window starts here)
export E2E_T0=$(( $(date +%s) - 60 ))
run() {
  local name="$1"; shift
  echo "== $name"
  bash "scripts/$name.sh" "$@" || fail "$name"
  echo "OK $name"
}
run smoke-flow http://localhost:8080 http://localhost:8081
run smoke-chart http://localhost:8082
run smoke-reading http://localhost:8083
run smoke-identity-payment http://localhost:8084 http://localhost:8085 http://localhost:8025 http://localhost:3001
run smoke-qa http://localhost:8084 http://localhost:8083 http://localhost:8025
run smoke-web http://localhost:3001
run e2e-observability http://localhost:9090 http://localhost:3200 http://localhost:3100 http://localhost:3000
# webhook-auth: payment-svc's own SePay check (the only authentication of the public /webhooks/sepay route) refuses a
# call without the key. The route itself (Exact path, body cap, rate limit) is checked through Traefik by
# scripts/e2e-edge.sh in the bootstrap job.
code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -X POST -H 'Content-Type: application/json' \
  --data '{"id":1,"transferType":"in","transferAmount":1000}' http://localhost:8085/webhooks/sepay) || code=000
[ "$code" = 401 ] || fail "webhook-auth: POST /webhooks/sepay without the key got $code, want 401"
echo "OK webhook-auth"

# --- 5. no app container restarted during the whole run (startup gating: wait-for-postgres init containers,
# kafkaTopics readiness). RESTARTS > 0 here means a service crash-looped on a dependency it should have waited for.
restarts=$(kubectl -n apps get pods -o jsonpath='{range .items[*]}{.metadata.name}{range .status.containerStatuses[*]} {.name}={.restartCount}{end}{"\n"}{end}')
echo "$restarts"
if grep -Eq '=[1-9][0-9]*( |$)' <<<"$restarts"; then fail "app-restarts-0: $(grep -E '=[1-9]' <<<"$restarts" | paste -sd';' -)"; fi
echo "OK app-restarts-0"

# --- 6. ops CronJobs: backup to MinIO, restore into scratch dbs, heartbeat. Runs last: it scales the apps to 0.
bash scripts/e2e-backup.sh || fail e2e-backup
echo "OK e2e-backup"
echo "k8s-e2e: ok"
