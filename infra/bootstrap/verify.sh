#!/usr/bin/env bash
# Checks a host set up by infra/bootstrap/bootstrap.sh (run as root, like the bootstrap).
# Usage: sudo [BOOTSTRAP_CLOSE_PUBLIC_SSH=1] infra/bootstrap/verify.sh [--expect-unchanged <log>]
# Prints `OK <check>` or `FAIL <check>: <reason>` per check and exits 1 if any check failed (all checks run, so one
# run shows the whole picture). Cluster checks are deadline loops on observable state (VERIFY_DEADLINE_S, default 180).
#   k3s-ready                  every node Ready
#   traefik-clusterip          Service kube-system/traefik is ClusterIP (no LoadBalancer, servicelb is disabled)
#   sealed-controller-ready    Deployment kube-system/sealed-secrets-controller rolled out
#   argocd-ready               every Deployment/StatefulSet in argocd has all replicas ready
#   no-public-listeners        nothing listens on 0.0.0.0/[::]/* ports 80, 443, 8080; ufw is active, denies incoming
#                              and allows only lo, tailscale0, the pod/service CIDRs and (SSH guard) the sshd ports, so 6443
#                              is reachable only from those
#   ssh-kept-without-tailscale ufw allows every sshd port, unless BOOTSTRAP_CLOSE_PUBLIC_SSH=1 (same value as for the
#                              bootstrap run) and Tailscale is connected: then none of them is public
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

# Same predicates as the bootstrap: tailscale_connected (logged in and online, not merely a tailscale0 link),
# ssh_ports (every port sshd listens on) and managed_rule_regex (the ufw rules bootstrap owns).
# shellcheck source=lib/hostnet.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/hostnet.sh"

check_listeners_and_firewall() {
  local listeners public status added extra ports
  if ! command -v ss >/dev/null 2>&1; then fail no-public-listeners "ss not found"; return; fi
  if ! command -v ufw >/dev/null 2>&1; then fail no-public-listeners "ufw not found"; return; fi
  listeners=$(ss -ltnH 2>&1) || { fail no-public-listeners "ss failed: $listeners"; return; }
  public=$(awk '{print $4}' <<<"$listeners" | grep -E '^(0\.0\.0\.0|\[::\]|\*):(80|443|8080)$' | sort -u | paste -sd, -)
  if [ -n "$public" ]; then fail no-public-listeners "public listener(s) $public"; return; fi
  status=$(ufw status verbose 2>&1) || { fail no-public-listeners "ufw status failed: $status"; return; }
  grep -q '^Status: active' <<<"$status" || { fail no-public-listeners "ufw is not active"; return; }
  grep -q '^Default: deny (incoming)' <<<"$status" || { fail no-public-listeners "ufw default incoming is not deny"; return; }
  added=$(ufw show added 2>&1) || { fail no-public-listeners "ufw show added failed: $added"; return; }
  mapfile -t ports < <(ssh_ports)
  # Any allow rule outside the managed set could open 6443 (or more) to the world.
  extra=$(grep '^ufw ' <<<"$added" | grep -Ev "$(managed_rule_regex "${ports[@]}")" | paste -sd';' -)
  if [ -n "$extra" ]; then fail no-public-listeners "unexpected ufw rule(s): $extra"; return; fi
  ok no-public-listeners
}

check_ssh_guard() {
  local added ports p open=() closed=()
  if ! command -v ufw >/dev/null 2>&1; then fail ssh-kept-without-tailscale "ufw not found"; return; fi
  added=$(ufw show added 2>&1) || { fail ssh-kept-without-tailscale "ufw show added failed: $added"; return; }
  mapfile -t ports < <(ssh_ports)
  for p in "${ports[@]}"; do
    if grep -qx "ufw allow $p/tcp" <<<"$added"; then open+=("$p"); else closed+=("$p"); fi
  done
  if public_ssh_closing; then
    if [ "${#open[@]}" -gt 0 ]; then fail ssh-kept-without-tailscale "BOOTSTRAP_CLOSE_PUBLIC_SSH=1 and Tailscale connected, but SSH port(s) $(IFS=,; echo "${open[*]}") are still public"
    else ok "ssh-kept-without-tailscale (opted in, Tailscale connected: public SSH closed)"; fi
  else
    if [ "${#closed[@]}" -gt 0 ]; then fail ssh-kept-without-tailscale "public SSH must stay open (no BOOTSTRAP_CLOSE_PUBLIC_SSH=1 or Tailscale not connected), but ufw does not allow sshd port(s) $(IFS=,; echo "${closed[*]}")"
    elif tailscale_connected; then ok "ssh-kept-without-tailscale (Tailscale connected, public SSH kept until BOOTSTRAP_CLOSE_PUBLIC_SSH=1; sshd on $(IFS=,; echo "${ports[*]}"))"
    else ok "ssh-kept-without-tailscale (sshd on $(IFS=,; echo "${ports[*]}"))"; fi
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
