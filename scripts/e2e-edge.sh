#!/usr/bin/env bash
# Usage: scripts/e2e-edge.sh   (CI job `bootstrap` only, on its k3s; never run on the dev machine)
# The public edge through k3s's real Traefik: applies the prod Ingresses and Traefik Middlewares rendered from
# deploy/k8s/overlays/prod/apps, with stub backends (traefik/whoami) behind the Services `web` and `payment-svc` in ns
# apps (GITOPS_CI_MODE=1 leaves prod-apps unsynced, so no prod image runs there). Then, through a port-forward to
# kube-system/svc/traefik with Host <APP_DOMAIN> and a Cf-Connecting-IP header (as cloudflared sends it), checks:
#   webhook-route    POST /webhooks/sepay reaches payment-svc
#   webhook-exact    /webhooks/sepay/x, /webhooks/paypal, /webhooks and /admin/... never reach payment-svc
#   body-cap         a 100 KiB POST /webhooks/sepay gets 413 before payment-svc
#   rate-limit       a burst on /webhooks/sepay from one client IP gets 429; another client IP still passes
#   strict-limit     a burst on /vi/dang-nhap gets 429 within 25 requests; / for that IP still passes
# The 401 of payment-svc for a webhook without the key is checked against the real service in the kind job
# (scripts/k8s-e2e.sh, webhook-auth). Prints `OK <check>` / `FAIL <check>: <reason>`; the first failure exits 1.
set -euo pipefail
cd "$(dirname "$0")/.."

WHOAMI_IMAGE="traefik/whoami:v1.11.0@sha256:200689790a0a0ea48ca45992e0450bc26ccab5307375b41c84dfc4f2475937ab"
PORT="${EDGE_PORT:-18081}"
fail() { echo "FAIL $1: $2" >&2; exit 1; }
tmp="$(mktemp -d)"
pf_pid=""
cleanup() {
  [ -z "$pf_pid" ] || kill "$pf_pid" 2>/dev/null || true # catch-ok: the port-forward may already have exited
  rm -rf "$tmp"
}
trap cleanup EXIT

domain="$(yq -r '.data.APP_DOMAIN' deploy/k8s/overlays/prod/domain/domain.yaml | tr -d '\r')"
[ -n "$domain" ] || fail setup "no APP_DOMAIN in deploy/k8s/overlays/prod/domain/domain.yaml"

# --- 1. the prod edge objects, exactly as rendered for Argo CD
kubectl kustomize deploy/k8s/overlays/prod/apps | yq 'select(.kind == "Ingress" or .kind == "Middleware")' >"$tmp/edge.yaml"
grep -q '^kind: Ingress' "$tmp/edge.yaml" && grep -q '^kind: Middleware' "$tmp/edge.yaml" || fail setup "no Ingress/Middleware rendered"
kubectl apply -f "$tmp/edge.yaml"

# --- 2. stub backends: whoami answers every path with its pod name (`Hostname: stub-<svc>-...`)
stub() { # stub <service name> <service port>
  cat <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: {name: stub-$1, namespace: apps}
spec:
  replicas: 1
  selector: {matchLabels: {app: stub-$1}}
  template:
    metadata: {labels: {app: stub-$1}}
    spec:
      securityContext: {runAsNonRoot: true, runAsUser: 65532}
      containers:
        - name: whoami
          image: $WHOAMI_IMAGE
          args: [--port, "8080"]
          ports: [{name: http, containerPort: 8080}]
          resources: {requests: {cpu: 10m, memory: 32Mi}, limits: {memory: 32Mi}}
          readinessProbe: {httpGet: {path: /, port: http}, periodSeconds: 2}
---
apiVersion: v1
kind: Service
metadata: {name: $1, namespace: apps}
spec:
  selector: {app: stub-$1}
  ports: [{name: http, port: $2, targetPort: http}]
---
EOF
}
{ stub web 3000; stub payment-svc 8085; } | kubectl apply -f -
kubectl -n apps rollout status deploy/stub-web --timeout=180s
kubectl -n apps rollout status deploy/stub-payment-svc --timeout=180s

# --- 3. port-forward to Traefik (the internal ingress, as verify-deploy uses it)
kubectl -n kube-system port-forward svc/traefik "$PORT:80" >"$tmp/pf.log" 2>&1 &
pf_pid=$!

# req <client ip> <method> <path> [curl args...]: prints `<status> <backend>` (backend = stub-web, stub-payment-svc or -)
req() {
  local ip="$1" method="$2" path="$3" code
  shift 3
  code="$(curl -sS -o "$tmp/body" -w '%{http_code}' --max-time 10 -X "$method" -H "Host: $domain" \
    -H "Cf-Connecting-IP: $ip" "$@" "http://127.0.0.1:$PORT$path")" || code=000
  printf '%s %s\n' "$code" "$(sed -n 's/^Hostname: \(stub-[a-z-]*\)-[a-z0-9]*-[a-z0-9]*\r\{0,1\}$/\1/p' "$tmp/body" | head -n 1 | grep . || echo -)"
}

# Traefik picks up new Ingresses within seconds; wait on the routed state, not a sleep.
deadline=$((SECONDS + 120))
until [ "$(req 192.0.2.1 POST /webhooks/sepay)" = "200 stub-payment-svc" ]; do
  [ "$SECONDS" -lt "$deadline" ] || fail webhook-route "POST /webhooks/sepay never reached payment-svc: $(req 192.0.2.1 POST /webhooks/sepay); $(tail -n 3 "$tmp/pf.log")"
  sleep 2
done
echo "OK webhook-route"

for p in /webhooks/sepay/x /webhooks/paypal /webhooks /admin/orders /prometheus /orders; do
  got="$(req 192.0.2.2 POST "$p")"
  [ "${got#* }" != stub-payment-svc ] || fail webhook-exact "$p reached payment-svc ($got)"
done
echo "OK webhook-exact"

head -c 102400 /dev/zero | tr '\0' 'a' >"$tmp/big"
got="$(req 192.0.2.3 POST /webhooks/sepay -H 'Content-Type: application/json' --data-binary "@$tmp/big")"
[ "${got%% *}" = 413 ] || fail body-cap "100 KiB body got $got, want 413"
echo "OK body-cap"

# ratelimit-webhook: burst 30 -> a burst of 60 from one IP must see 429; a fresh IP still gets through.
n429=0
for _ in $(seq 1 60); do
  [ "$(req 192.0.2.4 POST /webhooks/sepay | cut -d' ' -f1)" != 429 ] || n429=$((n429 + 1))
done
[ "$n429" -gt 0 ] || fail rate-limit "60 requests from one client IP got no 429"
[ "$(req 192.0.2.5 POST /webhooks/sepay)" = "200 stub-payment-svc" ] || fail rate-limit "another client IP was limited too (limit not per Cf-Connecting-IP)"
echo "OK rate-limit ($n429 of 60 limited)"

# ratelimit-strict: burst 20 per minute on login -> 25 quick requests must see 429; the site limit still lets / through.
n429=0
for _ in $(seq 1 25); do
  [ "$(req 192.0.2.6 GET /vi/dang-nhap | cut -d' ' -f1)" != 429 ] || n429=$((n429 + 1))
done
[ "$n429" -gt 0 ] || fail strict-limit "25 login requests from one client IP got no 429"
[ "$(req 192.0.2.6 GET /)" = "200 stub-web" ] || fail strict-limit "/ was limited by the login limit"
echo "OK strict-limit ($n429 of 25 limited)"

kubectl -n apps delete deploy/stub-web deploy/stub-payment-svc svc/web svc/payment-svc --wait=false >/dev/null
kubectl delete -f "$tmp/edge.yaml" --wait=false >/dev/null
echo "e2e-edge: ok"
