#!/usr/bin/env bash
# Usage: scripts/e2e-observability.sh [PROM_URL] [TEMPO_URL] [LOKI_URL] [GRAFANA_URL]   (curl + grep/sed only)
# Asserts that the stack's telemetry arrived in otel-lgtm after the smoke scripts ran (kind e2e, CI job k8s-e2e).
# Each check polls every E2E_OBS_POLL_S (5) seconds until it passes or E2E_OBS_DEADLINE_S (120) runs out, then
# prints `OK <check>` or `FAIL <check>: <last response>`. Every check runs; the exit code is non-zero if any failed.
# E2E_T0 (unix seconds) is the start of the search window for traces and logs (default: one hour ago).
set -uo pipefail
PROM="${1:-http://localhost:9090}"
TEMPO="${2:-http://localhost:3200}"
LOKI="${3:-http://localhost:3100}"
GRAFANA="${4:-http://localhost:3000}"
DEADLINE_S="${E2E_OBS_DEADLINE_S:-120}"
POLL_S="${E2E_OBS_POLL_S:-5}"
T0="${E2E_T0:-$(( $(date +%s) - 3600 ))}"

# get URL [--data-urlencode k=v ...] -> response body on stdout (curl errors too); non-zero on transport/HTTP error.
get() { local url="$1"; shift; curl -sS --fail-with-body --max-time 10 --get "$@" "$url" 2>&1; }
prom() { get "$PROM/api/v1/query" --data-urlencode "query=$1"; }
# has TEXT ERE -> TEXT contains a match of ERE
has() { printf '%s' "$1" | grep -E "$2" >/dev/null; }

probe_trace_cross_service() {
  local r
  r=$(get "$TEMPO/api/search" \
    --data-urlencode 'q={resource.service.name="order-svc"} >> {resource.service.name="notify-svc"}' \
    --data-urlencode "start=$T0" --data-urlencode "end=$(date +%s)") || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  has "$r" '"traceID" *: *"[0-9a-fA-F]+"'
}

probe_red_http() {
  local r j
  r=$(prom 'count by (job) (http_server_request_duration_seconds_count)') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  for j in order-svc notify-svc chart-svc reading-tuvi identity-svc payment-svc; do
    has "$r" "\"job\" *: *\"$j\"" || return 1
  done
}

probe_red_consumer() {
  local r j
  r=$(prom 'count by (job) (messaging_process_duration_seconds_count{outcome="processed"})') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  for j in notify-svc identity-svc; do
    has "$r" "\"job\" *: *\"$j\"" || return 1
  done
}

# Only platform metrics carry `outcome`; a series without it on this metric came from an agent (double count).
probe_no_agent_double_count() {
  local r
  r=$(prom 'count(messaging_process_duration_seconds_count{outcome=""})') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  # empty result (no series) or a value of 0 passes
  if has "$r" '"result" *: *\[\]'; then return 0; fi
  has "$r" '"value" *: *\[[^]]*"0"\]'
}

probe_consumer_lag() {
  local r
  r=$(prom 'redpanda_kafka_consumer_group_lag_max{redpanda_group="notify-svc"}') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  has "$r" '"redpanda_group" *: *"notify-svc"'
}

probe_outbox_lag() {
  local r
  r=$(prom 'platform_outbox_lag_seconds{job="order-svc"}') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  has "$r" '"job" *: *"order-svc"'
}

probe_logs_with_trace() {
  local r
  r=$(get "$LOKI/loki/api/v1/query_range" \
    --data-urlencode 'query={service_name="notify-svc"} |= "event processed"' \
    --data-urlencode "start=${T0}000000000" --data-urlencode "end=$(date +%s)000000000" \
    --data-urlencode 'limit=100') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r"
  # trace_id comes back as structured metadata (or a stream label); an all-zero id means "no span".
  local ids  # capture first so an early-closing grep cannot SIGPIPE the upstream under pipefail
  ids=$(printf '%s' "$r" | grep -Eo '"trace_id" *: *"[0-9a-f]{32}"')
  printf '%s' "$ids" | grep -Ev '"0{32}"' >/dev/null
}

probe_dashboards() {
  local uid code
  for uid in platform-red platform-messaging; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$GRAFANA/api/dashboards/uid/$uid" 2>&1)
    printf '%s=%s ' "$uid" "$code"
    [ "$code" = "200" ] || return 1
  done
}


# Alert rules as code (deploy/k8s/platform/grafana/alerting), Grafana basic auth admin:admin (otel-lgtm image default).
ALERT_UIDS="error-rate-high latency-p95-high pod-restart-loop consumer-lag-rising disk-usage-high deploy-failed"
gget() { get "$1" -u admin:admin; }

probe_alert_rules_loaded() {
  local r u missing=""
  r=$(gget "$GRAFANA/api/v1/provisioning/alert-rules") || { printf '%s' "$r"; return 1; }
  for u in $ALERT_UIDS; do has "$r" "\"uid\" *: *\"$u\"" || missing="$missing $u"; done
  [ -z "$missing" ] || { printf 'missing uids:%s in %s' "$missing" "$(printf '%s' "$r" | cut -c1-200)"; return 1; }
  printf 'all %s rules provisioned' "$(wc -w <<<"$ALERT_UIDS" | tr -d ' ')"
}

# Every rule of group `platform` evaluated without error: health ok, or nodata (e.g. deploy-failed has no Argo CD target in kind).
probe_alert_rules_healthy() {
  local r healths n
  r=$(gget "$GRAFANA/api/prometheus/grafana/api/v1/rules") || { printf '%s' "$r"; return 1; }
  has "$r" '"name" *: *"platform"' || { printf 'no rule group "platform": %s' "$(printf '%s' "$r" | cut -c1-200)"; return 1; }
  healths=$(printf '%s' "$r" | grep -Eo '"health" *: *"[a-z]*"' | tr -d ' ' | sort | uniq -c | tr '\n' ' ') || true
  printf 'health: %s' "$healths"
  n=$(printf '%s' "$r" | grep -Eo '"health" *: *"(ok|nodata)"' | wc -l | tr -d ' ')
  [ "$n" -ge 6 ] || return 1
  ! has "$r" '"health" *: *"(error|unknown)"'
}

probe_contact_point() {
  local r
  r=$(gget "$GRAFANA/api/v1/provisioning/contact-points") || { printf '%s' "$r"; return 1; }
  printf '%s' "$r" | sed 's/"bottoken" *: *"[^"]*"/"bottoken":"<redacted>"/g'
  has "$r" '"name" *: *"telegram"'
}

# Spec §10: Prometheus 15d, Loki 168h (shown as 1w), Tempo 168h.
probe_retention() {
  local r
  r=$(get "$PROM/api/v1/status/flags") || { printf '%s' "$r"; return 1; }
  has "$r" '"storage.tsdb.retention.time" *: *"15d"' || { printf 'prometheus retention: %s' "$(printf '%s' "$r" | grep -Eo '"storage.tsdb.retention[a-z.]*" *: *"[^"]*"' | tr '\n' ' ')"; return 1; }
  r=$(get "$LOKI/config") || { printf '%s' "$r"; return 1; }
  has "$r" 'retention_period: *(1w|168h)' || { printf 'loki retention_period not 1w/168h'; return 1; }
  has "$r" 'retention_enabled: *true' || { printf 'loki compactor retention_enabled not true'; return 1; }
  r=$(get "$TEMPO/status/config") || { printf '%s' "$r"; return 1; }
  has "$r" 'block_retention: *(168h0m0s|1w)' || { printf 'tempo block_retention not 168h'; return 1; }
  printf 'prometheus 15d, loki 1w, tempo 168h'
}


# The alerts are silent on NoData, so prove their inputs exist with the labels the rules use (deploy-failed is exempt: no Argo CD in kind).
probe_metric_present_restarts() {
  local r
  r=$(prom 'count by (k8s_namespace_name, k8s_pod_name) (k8s_container_restarts{k8s_namespace_name="apps"})') || { printf '%s' "$r"; return 1; }
  printf '%s' "$r" | cut -c1-300
  has "$r" '"k8s_namespace_name" *: *"apps"' && has "$r" '"k8s_pod_name" *: *"[^"]+"'
}

probe_metric_present_disk() {
  local r m
  for m in k8s_node_filesystem_usage_bytes k8s_node_filesystem_capacity_bytes; do
    r=$(prom "max by (k8s_node_name) ($m)") || { printf '%s' "$r"; return 1; }
    printf '%s: %s ' "$m" "$(printf '%s' "$r" | cut -c1-150)"
    has "$r" '"k8s_node_name" *: *"[^"]+"' || return 1
  done
}

# check NAME PROBE -> polls PROBE until it passes or the deadline passes
check() {
  local name="$1" probe="$2" last deadline=$((SECONDS + DEADLINE_S))
  while :; do
    if last=$("$probe"); then
      echo "OK $name"
      return 0
    fi
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep "$POLL_S"
  done
  echo "FAIL $name: $(printf '%s' "$last" | tr -d '\r' | tr '\n' ' ' | cut -c1-600)"
  return 1
}

failed=0
check trace-cross-service probe_trace_cross_service || failed=1
check red-http probe_red_http || failed=1
check red-consumer probe_red_consumer || failed=1
check no-agent-double-count probe_no_agent_double_count || failed=1
check consumer-lag probe_consumer_lag || failed=1
check outbox-lag probe_outbox_lag || failed=1
check logs-with-trace probe_logs_with_trace || failed=1
check dashboards probe_dashboards || failed=1
check alert-rules-loaded probe_alert_rules_loaded || failed=1
check alert-rules-healthy probe_alert_rules_healthy || failed=1
check contact-point probe_contact_point || failed=1
check retention probe_retention || failed=1
check metric-present-restarts probe_metric_present_restarts || failed=1
check metric-present-disk probe_metric_present_disk || failed=1
exit "$failed"
