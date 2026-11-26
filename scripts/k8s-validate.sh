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

# Shared jq helpers for the overlay checks.
JQ_DEFS='
def podspec:
  if .kind == "Pod" then .spec
  elif .kind == "CronJob" then .spec.jobTemplate.spec.template.spec
  else .spec.template.spec end;
def id: "\(.kind)/\(.metadata.name)";
def ns: (.metadata.namespace // "default");
'

# prod-no-secrets: prod builds contain no plaintext Secret objects (Plan 4 supplies SealedSecrets).
JQ_NOSECRET='.[] | select(type == "object" and .kind == "Secret") | "POLICY: prod-no-secrets Secret/\(.metadata.name): plaintext Secret in a prod build (use SealedSecret)"'

# prod-no-fake-sepay: the prod build must not carry the fake compose receiving account (an unset account makes
# SePay orders fail safe with 503; a fake one would be shown to real users).
JQ_NOFAKESEPAY='.[] | select(type == "object") | . as $o
  | [.. | objects | select(has("name") and has("value") and (.value | type) == "string")
      | select(.name | test("^SEPAY_(BANK_CODE|ACCOUNT_NUMBER|ACCOUNT_NAME)$"))
      | select(.value == "TEST BANK" or (.value | test("^0+$")))]
  | .[] | "POLICY: prod-no-fake-sepay \($o.kind)/\($o.metadata.name): \(.name) has the fake test value \"\(.value)\""'

# prod-images: every app (namespace apps) image in prod is ghcr.io/nguyentuan2686/<workload name>:<tag>.
JQ_PRODIMAGES="$JQ_DEFS"'
.[] | select(type == "object" and (.kind | IN("Deployment","StatefulSet","DaemonSet","Job","CronJob"))) | select(ns == "apps") as $o
| (($o | podspec) | (.containers // []) + (.initContainers // []))[]
| select((.image // "") | startswith("ghcr.io/nguyentuan2686/" + $o.metadata.name + ":") | not)
| "POLICY: prod-images \($o | id): container \(.name): image \"\(.image // "")\" must be ghcr.io/nguyentuan2686/\($o.metadata.name):<tag>"'

# secret-refs: every envFrom secretRef / env secretKeyRef names a Secret of this build in the same namespace,
# and every referenced key exists in it (non-optional refs only).
JQ_SECRETREFS="$JQ_DEFS"'
(map(select(type == "object" and .kind == "Secret")
     | {key: "\(ns)/\(.metadata.name)", value: (((.data // {}) + (.stringData // {})) | keys)}) | from_entries) as $secrets
| .[] | select(type == "object" and (.kind | IN("Pod","Deployment","StatefulSet","DaemonSet","Job","CronJob","ReplicaSet"))) as $o
| (($o | podspec) | (.containers // []) + (.initContainers // []))[] as $c
| ( ($c.envFrom // [])[] | select(.secretRef != null and (.secretRef.optional | not))
    | "\($o | ns)/\(.secretRef.name)" as $k
    | if $secrets[$k] == null then "POLICY: secret-refs \($o | id): container \($c.name): envFrom secretRef \($k) names no Secret in this build" else empty end ),
  ( ($c.env // [])[] | select(.valueFrom.secretKeyRef != null and (.valueFrom.secretKeyRef.optional | not))
    | .valueFrom.secretKeyRef as $r | "\($o | ns)/\($r.name)" as $k
    | if $secrets[$k] == null then "POLICY: secret-refs \($o | id): container \($c.name): secretKeyRef \($k) names no Secret in this build"
      elif ($secrets[$k] | index($r.key)) == null then "POLICY: secret-refs \($o | id): container \($c.name): secretKeyRef \($k) has no key \($r.key)"
      else empty end )'

# Overlay kind: the `overlay` file in a fixture, else the directory name (prod | e2e).
overlay_kind() { # $1 = dir
  if [ -f "$1/overlay" ]; then tr -d '\r\n' <"$1/overlay"; else basename "$1"; fi
}

# Fails closed: any parse/jq error, or empty input, is a violation (non-zero + POLICY line).
policy() { # $1 = dir; stdin = kustomize output
  local json res
  json=$(yq -o=json '.' | jq -s '.') || { echo "POLICY: internal $1: cannot parse kustomize output"; return 1; }
  if [ "$(jq 'length' <<<"$json")" = "0" ]; then echo "POLICY: internal $1: kustomize output is empty"; return 1; fi
  res=$(jq -r "$JQ_POLICY" <<<"$json") || { echo "POLICY: internal $1: policy evaluation failed"; return 1; }
  [ -n "$res" ] && echo "$res"
  case "$(overlay_kind "$1")" in
    prod)
      res=$(jq -r "$JQ_NOSECRET" <<<"$json") || { echo "POLICY: internal $1: prod-no-secrets check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_PRODIMAGES" <<<"$json") || { echo "POLICY: internal $1: prod-images check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_NOFAKESEPAY" <<<"$json") || { echo "POLICY: internal $1: prod-no-fake-sepay check failed"; return 1; }
      [ -n "$res" ] && echo "$res" ;;
    e2e)
      res=$(jq -r "$JQ_SECRETREFS" <<<"$json") || { echo "POLICY: internal $1: secret-refs check failed"; return 1; }
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


# dashboards: Grafana dashboards as code. Each JSON parses, uids are unique, every panel target names a datasource uid,
# and every metric identifier used in a query (targets[].expr, variable queries) is listed in metric-names.txt.
# $1 = dir holding dashboards/*.json and metric-names.txt. Fails closed: missing files, parse errors or no dashboards fail.
# Metric identifiers: the brief's suffixes plus _total, and any name directly followed by a label selector.
METRIC_RE='[a-z_][a-z0-9_]*(?:_bucket|_count|_sum|_seconds|_total|_lag_max|_lag_sum)\b|[a-z_][a-z0-9_]*(?=\{)'
check_dashboards() {
  local dir="$1" f n=0 rc=0 uids="" uid res m names
  echo "== $dir (dashboards)"
  if [ ! -d "$dir/dashboards" ]; then echo "POLICY: dashboards $dir: dashboards/ directory missing"; return 1; fi
  if [ ! -s "$dir/metric-names.txt" ]; then echo "POLICY: dashboards $dir: metric-names.txt missing or empty"; return 1; fi
  names=$(tr -d '\r' <"$dir/metric-names.txt" | sed '/^[[:space:]]*$/d')
  for f in "$dir"/dashboards/*.json; do
    [ -f "$f" ] || continue
    n=$((n + 1))
    uid=$(jq -er '.uid | select(type == "string" and length > 0)' "$f" | tr -d '\r') || { echo "POLICY: dashboards $f: not valid JSON or no uid"; rc=1; continue; }
    if grep -qxF "$uid" <<<"$uids"; then echo "POLICY: dashboards $f: duplicate uid $uid"; rc=1; fi
    uids+="$uid"$'\n'
    res=$(jq -r --arg f "$f" '[.. | objects | select(has("targets")) | .targets[] | select((.datasource.uid // "") == "") | (.refId // "?")]
      | .[] | "POLICY: dashboards \($f): target \(.) has no datasource.uid"' "$f" | tr -d '\r') \
      || { echo "POLICY: dashboards $f: datasource check failed"; rc=1; continue; }
    if [ -n "$res" ]; then echo "$res"; rc=1; fi
    res=$(jq -r --arg re "$METRIC_RE" '[(.. | objects | select(has("expr")) | .expr), (.templating.list[]? | (.query | if type == "object" then .query else . end) // empty)]
      | .[] | strings | scan($re)' "$f" | tr -d '\r') \
      || { echo "POLICY: dashboards $f: metric extraction failed"; rc=1; continue; }
    while IFS= read -r m; do
      [ -z "$m" ] && continue
      if ! grep -qxF "$m" <<<"$names"; then echo "POLICY: dashboards $f: metric $m is not listed in metric-names.txt"; rc=1; fi
    done <<<"$(sort -u <<<"$res")"
  done
  if [ "$n" -eq 0 ]; then echo "POLICY: dashboards $dir: no dashboards/*.json found"; return 1; fi
  return $rc
}

self_test() {
  local rc=0 d name check out
  for d in scripts/testdata/k8s-validate/*/; do
    name=$(basename "$d"); check=$(tr -d '\r\n' <"$d/expect") # `expect` holds the POLICY check the fixture must trigger
    if [ -d "$d/dashboards" ]; then out=$(check_dashboards "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }
    else out=$(SKIP_KUBECONFORM=1 validate_one "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }; fi
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
[ $# -eq 0 ] && { check_dashboards deploy/k8s/platform/grafana || fail=1; }
[ $fail -eq 0 ] && echo "k8s-validate: ok" || echo "k8s-validate: FAILED"
exit $fail
