#!/usr/bin/env bash
# Checks a host set up by infra/bootstrap/bootstrap.sh (run as root, like the bootstrap).
# Usage: sudo infra/bootstrap/verify.sh [--expect-unchanged <log>]
# Prints `OK <check>` or `FAIL <check>: <reason>` per check and exits 1 if any check failed (all checks run, so one
# run shows the whole picture). Cluster checks are deadline loops on observable state (VERIFY_DEADLINE_S, default 180).
#   k3s-ready                  every node Ready
#   traefik-clusterip          Service kube-system/traefik is ClusterIP (no LoadBalancer, servicelb is disabled)
#   sealed-controller-ready    Deployment kube-system/sealed-secrets-controller rolled out
#   argocd-ready               every Deployment/StatefulSet in argocd has all replicas ready
#   no-public-listeners        nothing listens on 0.0.0.0/[::]/* ports 80, 443, 8080; ufw is active, denies incoming
#                              and allows only lo, tailscale0, the pod/service CIDRs and (SSH guard) 22/tcp, so 6443
#                              is reachable only from those
#   ssh-kept-without-tailscale tailscale0 absent: ufw allows 22/tcp; tailscale0 up: it no longer does
#   second-run-no-changes      (--expect-unchanged) the log has no `CHANGED ` line and ends `bootstrap: 0 changed`
set -uo pipefail

deadline_s="${VERIFY_DEADLINE_S:-180}"
expect_log=""
while [ $# -gt 0 ]; do
  case "$1" in
    --expect-unchanged) expect_log="${2:?--expect-unchanged needs a log file}"; shift 2 ;;
    *) echo "verify: unknown argument $1" >&2; exit 2 ;;
  esac
done

failed=0
ok() { echo "OK $1"; }
fail() { echo "FAIL $1: $2"; failed=1; }

kc() {
  if [ -x /usr/local/bin/k3s ]; then /usr/local/bin/k3s kubectl "$@"; else kubectl "$@"; fi
}

# poll <fn>: re-run fn until it succeeds or the deadline passes; fn leaves its last reason in $reason.
reason=""
poll() {
  local end=$((SECONDS + deadline_s))
  while :; do
    "$1" && return 0
    [ "$SECONDS" -ge "$end" ] && return 1
    sleep 3
  done
}

nodes_ready() {
  local out
  out=$(kc get nodes -o jsonpath='{range .items[*]}{.metadata.name}={.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>&1) \
    || { reason="kubectl get nodes failed: $(head -c 200 <<<"$out")"; return 1; }
  [ -n "$out" ] || { reason="no nodes"; return 1; }
  if grep -qv '=True$' <<<"$out"; then reason="not Ready: $(grep -v '=True$' <<<"$out" | paste -sd, -)"; return 1; fi
}

traefik_clusterip() {
  local t
  t=$(kc -n kube-system get service traefik -o jsonpath='{.spec.type}' 2>&1) || { reason="service kube-system/traefik: $(head -c 200 <<<"$t")"; return 1; }
  [ "$t" = ClusterIP ] || { reason="type is $t"; return 1; }
}

sealed_ready() {
  local out
  out=$(kc -n kube-system rollout status deployment/sealed-secrets-controller --timeout=5s 2>&1) \
    || { reason="$(tail -n1 <<<"$out")"; return 1; }
}

argocd_ready() {
  local out bad
  out=$(kc -n argocd get deployments,statefulsets -o jsonpath='{range .items[*]}{.kind}/{.metadata.name} {.spec.replicas} {.status.readyReplicas}{"\n"}{end}' 2>&1) \
    || { reason="kubectl get failed: $(head -c 200 <<<"$out")"; return 1; }
  [ -n "$out" ] || { reason="no Deployment/StatefulSet in argocd"; return 1; }
  bad=$(awk '$2 != $3 {print $1 " " ($3 == "" ? 0 : $3) "/" $2}' <<<"$out" | paste -sd, -)
  [ -z "$bad" ] || { reason="not ready: $bad"; return 1; }
}

tailscale_up() { ip -o link show tailscale0 2>/dev/null | grep -q '[<,]UP[,>]'; }

# ufw user rules that may exist: anything else could open 6443 (or more) to the world.
allowed_rule='^ufw allow (in on (lo|tailscale0)|from 10\.42\.0\.0/16|from 10\.43\.0\.0/16|22/tcp)$'

check_listeners_and_firewall() {
  local listeners public status added extra
  if ! command -v ss >/dev/null 2>&1; then fail no-public-listeners "ss not found"; return; fi
  if ! command -v ufw >/dev/null 2>&1; then fail no-public-listeners "ufw not found"; return; fi
  listeners=$(ss -ltnH 2>&1) || { fail no-public-listeners "ss failed: $listeners"; return; }
  public=$(awk '{print $4}' <<<"$listeners" | grep -E '^(0\.0\.0\.0|\[::\]|\*):(80|443|8080)$' | sort -u | paste -sd, -)
  if [ -n "$public" ]; then fail no-public-listeners "public listener(s) $public"; return; fi
  status=$(ufw status verbose 2>&1) || { fail no-public-listeners "ufw status failed: $status"; return; }
  grep -q '^Status: active' <<<"$status" || { fail no-public-listeners "ufw is not active"; return; }
  grep -q '^Default: deny (incoming)' <<<"$status" || { fail no-public-listeners "ufw default incoming is not deny"; return; }
  added=$(ufw show added 2>&1) || { fail no-public-listeners "ufw show added failed: $added"; return; }
  extra=$(grep '^ufw ' <<<"$added" | grep -Ev "$allowed_rule" | paste -sd';' -)
  if [ -n "$extra" ]; then fail no-public-listeners "unexpected ufw rule(s): $extra"; return; fi
  ok no-public-listeners
}

check_ssh_guard() {
  local added
  if ! command -v ufw >/dev/null 2>&1; then fail ssh-kept-without-tailscale "ufw not found"; return; fi
  added=$(ufw show added 2>&1) || { fail ssh-kept-without-tailscale "ufw show added failed: $added"; return; }
  if tailscale_up; then
    if grep -qx 'ufw allow 22/tcp' <<<"$added"; then fail ssh-kept-without-tailscale "tailscale0 is up but 22/tcp is still public"
    else ok "ssh-kept-without-tailscale (tailscale0 up, public 22 dropped)"; fi
  else
    if grep -qx 'ufw allow 22/tcp' <<<"$added"; then ok ssh-kept-without-tailscale
    else fail ssh-kept-without-tailscale "tailscale0 is not up and ufw does not allow 22/tcp"; fi
  fi
}

check_second_run() {
  local log="$1" changed last
  [ -f "$log" ] || { fail second-run-no-changes "log $log not found"; return; }
  changed=$(grep '^CHANGED ' "$log" | paste -sd';' -)
  last=$(tail -n1 "$log" | tr -d '\r')
  if [ -n "$changed" ]; then fail second-run-no-changes "$changed"
  elif [ "$last" != "bootstrap: 0 changed" ]; then fail second-run-no-changes "last line is [$last], expected [bootstrap: 0 changed]"
  else ok second-run-no-changes; fi
}

check() { # check <name> <fn>
  reason=""
  if poll "$2"; then ok "$1"; else fail "$1" "${reason:-timed out}"; fi
}

check k3s-ready nodes_ready
check traefik-clusterip traefik_clusterip
check sealed-controller-ready sealed_ready
check argocd-ready argocd_ready
check_listeners_and_firewall
check_ssh_guard
[ -n "$expect_log" ] && check_second_run "$expect_log"

exit "$failed"
