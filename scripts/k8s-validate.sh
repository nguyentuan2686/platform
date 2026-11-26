#!/usr/bin/env bash
# Validate Kustomize output: kubeconform (schema) + policy checks (yq/jq).
# Usage: scripts/k8s-validate.sh [--self-test] [overlay...]
# Local (no native kubectl): docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh
set -uo pipefail

KUBE_VERSION="${KUBE_VERSION:-1.33.0}" # kind node version used by the e2e job
cd "$(dirname "$0")/.."

# Reads a JSON array of manifests on stdin; prints `POLICY: <check> <kind>/<name>: <reason>` lines.
JQ_POLICY='
def podspec:
  if (.kind == "Deployment" or .kind == "StatefulSet" or .kind == "DaemonSet" or .kind == "Job" or .kind == "ReplicaSet") then .spec.template.spec
  elif .kind == "CronJob" then .spec.jobTemplate.spec.template.spec
  else empty end;
def ns: (.metadata.namespace // "default");
def imgname: (.image | sub("@.*$"; "") | sub(":[^/:]*$"; "") | split("/") | last);
def imgtag: (.image | sub("@.*$"; "") | split("/") | last | if test(":") then split(":")[1] else "" end);
def id: "\(.kind)/\(.metadata.name)";
def fail($check; $o; $c; $why): "POLICY: \($check) \($o|id): container \($c.name): \($why)";
.[] | select(type == "object") as $o
| ($o | podspec) as $ps
| ($ps | (.containers // []) + (.initContainers // [])) as $all
| ($ps.containers // []) as $main
| (
  # resources
  ($all[] | . as $c
    | ($c.resources.requests.memory) as $rm | ($c.resources.limits.memory) as $lm
    | (if $rm == null or $lm == null then fail("resources"; $o; $c; "memory request and limit are required")
       elif $rm != $lm then fail("resources"; $o; $c; "requests.memory (\($rm)) != limits.memory (\($lm))") else empty end),
      (if $c.resources.requests.cpu == null then fail("resources"; $o; $c; "requests.cpu is required") else empty end),
      (if $c.resources.limits.cpu != null then fail("resources"; $o; $c; "limits.cpu must not be set") else empty end)),
  # probes (apps namespace)
  (if ($o|ns) == "apps" then $main[] | . as $c
    | (if $c.livenessProbe.httpGet.path != "/health/live" then fail("probes"; $o; $c; "livenessProbe must GET /health/live") else empty end),
      (if $c.readinessProbe.httpGet.path != "/health/ready" then fail("probes"; $o; $c; "readinessProbe must GET /health/ready") else empty end)
    else empty end),
  # startup (Java services)
  ($main[] | . as $c | select(($c | imgname) | test("(^|-)(order-svc|reading-tuvi|identity-svc|payment-svc)$"))
    | (($c.startupProbe.periodSeconds // 10) * ($c.startupProbe.failureThreshold // 3)) as $t
    | if $c.startupProbe == null or $t < 180 then fail("startup"; $o; $c; "startupProbe must allow >= 180s (period*failureThreshold = \($t))") else empty end),
  # nonroot (apps namespace)
  (if ($o|ns) == "apps" then $all[] | . as $c
    | (($c.securityContext.runAsNonRoot // $ps.securityContext.runAsNonRoot) == true) as $nr
    | (($c.securityContext.runAsUser // $ps.securityContext.runAsUser) | type) as $ut
    | if ($nr | not) or $ut != "number" then fail("nonroot"; $o; $c; "needs runAsNonRoot: true and a numeric runAsUser") else empty end
    else empty end),
  # no-latest
  ($all[] | . as $c | select(($c | imgtag) == "latest" or (($c.image | test("@sha256:")) | not) and (($c | imgtag) == ""))
    | fail("no-latest"; $o; $c; "image \($c.image) must have a pinned tag (not latest)"))
)'

# Extra rule applied only to prod overlay builds: no plaintext Secret objects.
JQ_NOSECRET='.[] | select(type == "object" and .kind == "Secret") | "POLICY: no-secret Secret/\(.metadata.name): plaintext Secret in a prod build (use SealedSecret)"'

policy() { # $1 = label (path); stdin = kustomize output
  local json
  json=$(yq -o=json '.' | jq -s '.') || { echo "POLICY: parse $1: cannot parse kustomize output"; return 1; }
  jq -r "$JQ_POLICY" <<<"$json"
  case "$1" in */prod|*/prod/) jq -r "$JQ_NOSECRET" <<<"$json" ;; esac
}

build() { kubectl kustomize "$1"; }

validate_one() {
  local dir="$1" out rc=0 pol
  if [ ! -d "$dir" ]; then echo "ERROR: $dir does not exist"; return 1; fi
  echo "== $dir"
  out=$(build "$dir") || { echo "ERROR: kustomize build failed for $dir"; return 1; }
  if [ -z "${SKIP_KUBECONFORM:-}" ]; then
    kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" -ignore-missing-schemas <<<"$out" || rc=1
  fi
  pol=$(policy "$dir" <<<"$out")
  if [ -n "$pol" ]; then echo "$pol"; rc=1; fi
  return $rc
}

self_test() {
  local rc=0 d name check out
  for d in scripts/testdata/k8s-validate/*/; do
    name=$(basename "$d"); check="${name#bad-}"
    out=$(SKIP_KUBECONFORM=1 validate_one "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }
    if grep -q "^POLICY: $check " <<<"$out"; then echo "self-test ok: $name rejected with POLICY: $check"
    else echo "SELF-TEST FAIL: $name rejected without 'POLICY: $check':"; echo "$out"; rc=1; fi
  done
  return $rc
}

if [ "${1:-}" = "--self-test" ]; then self_test; exit $?; fi

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(deploy/k8s/platform)
  for d in deploy/k8s/overlays/*/; do [ -d "$d" ] && targets+=("${d%/}"); done
fi
fail=0
for t in "${targets[@]}"; do validate_one "${t%/}" || fail=1; done
[ $fail -eq 0 ] && echo "k8s-validate: ok" || echo "k8s-validate: FAILED"
exit $fail
