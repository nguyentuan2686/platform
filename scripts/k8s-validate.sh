#!/usr/bin/env bash
# Validate Kustomize output: kubeconform (schema) + policy checks (yq/jq).
# Usage: scripts/k8s-validate.sh [--self-test] [overlay...]
# Local (no native kubectl): docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh
set -euo pipefail

KUBE_VERSION="${KUBE_VERSION:-1.33.0}" # kind node version used by the e2e job
cd "$(dirname "$0")/.."

# Reads a JSON array of manifests on stdin; prints `POLICY: <check> <kind>/<name>: <reason>` lines.
JQ_POLICY='
def known: ["Pod","Deployment","StatefulSet","DaemonSet","Job","CronJob","ReplicaSet"];
def podspec:
  if .kind == "Pod" then .spec
  elif (.kind == "Deployment" or .kind == "StatefulSet" or .kind == "DaemonSet" or .kind == "Job" or .kind == "ReplicaSet") then .spec.template.spec
  elif .kind == "CronJob" then .spec.jobTemplate.spec.template.spec
  else .spec.template.spec end; # unknown kinds: used only to detect an embedded pod template
def ns: (.metadata.namespace // "default");
def image: (.image // "");
def imgname: (image | sub("@.*$"; "") | sub(":[^/:]*$"; "") | split("/") | (last // ""));
def imgtag: (image | sub("@.*$"; "") | split("/") | (last // "") | if test(":") then split(":")[1] else "" end);
def id: "\(.kind)/\(.metadata.name)";
def fail($check; $o; $c; $why): "POLICY: \($check) \($o|id): container \($c.name): \($why)";
def eff($c; $ps; $k): if $c.securityContext[$k] != null then $c.securityContext[$k] else $ps.securityContext[$k] end;
.[] | select(type == "object") as $o
| ($o | podspec) as $ps
| (($o.kind | IN(known[])) | not) as $unknown
| ($ps | (.containers // []) + (.initContainers // [])) as $all
| ($ps.containers // []) as $main
| (
  # every workload-bearing kind is checked; unknown kinds that carry a pod template, or known ones without a pod spec, fail
  (if $unknown and $ps != null and ($ps | type) == "object" and $ps.containers != null
     then "POLICY: workload-kind \($o|id): kind is not in the checked list (\(known|join(", "))) but carries a pod template"
     elif ($unknown | not) and ($ps | type) != "object" then "POLICY: workload-kind \($o|id): no pod spec found"
     else empty end),
  (if ($ps | type) == "object" and ($all | length) == 0 and ($unknown | not) then "POLICY: workload-kind \($o|id): no containers" else empty end),
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
    | eff($c; $ps; "runAsNonRoot") as $nr
    | eff($c; $ps; "runAsUser") as $uid
    | if $nr != true or ($uid | type) != "number" or $uid < 1 then fail("nonroot"; $o; $c; "effective runAsNonRoot must be true and runAsUser a numeric id > 0 (got runAsNonRoot=\($nr), runAsUser=\($uid))") else empty end
    else empty end),
  # no-latest
  ($all[] | . as $c | select(($c | imgtag) == "latest" or ((($c | image) | test("@sha256:")) | not) and (($c | imgtag) == ""))
    | fail("no-latest"; $o; $c; "image \"\($c | image)\" must have a pinned tag (not latest)"))
)'

# Extra rule applied only to prod overlay builds: no plaintext Secret objects.
JQ_NOSECRET='.[] | select(type == "object" and .kind == "Secret") | "POLICY: no-secret Secret/\(.metadata.name): plaintext Secret in a prod build (use SealedSecret)"'

# Fails closed: any parse/jq error, or empty input, is a violation (non-zero + POLICY line).
policy() { # $1 = label (path); stdin = kustomize output
  local json res
  json=$(yq -o=json '.' | jq -s '.') || { echo "POLICY: internal $1: cannot parse kustomize output"; return 1; }
  if [ "$(jq 'length' <<<"$json")" = "0" ]; then echo "POLICY: internal $1: kustomize output is empty"; return 1; fi
  res=$(jq -r "$JQ_POLICY" <<<"$json") || { echo "POLICY: internal $1: policy evaluation failed"; return 1; }
  [ -n "$res" ] && echo "$res"
  case "$1" in
    */prod|*/prod/)
      res=$(jq -r "$JQ_NOSECRET" <<<"$json") || { echo "POLICY: internal $1: secret check failed"; return 1; }
      [ -n "$res" ] && echo "$res" ;;
  esac
  return 0
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
  pol=$(policy "$dir" <<<"$out") || rc=1
  if [ -n "$pol" ]; then echo "$pol"; rc=1; fi
  return $rc
}

self_test() {
  local rc=0 d name check out
  for d in scripts/testdata/k8s-validate/*/; do
    name=$(basename "$d"); check=$(tr -d '\r\n' <"$d/expect") # `expect` holds the POLICY check the fixture must trigger
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
