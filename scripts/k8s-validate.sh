#!/usr/bin/env bash
# Validate Kustomize output: kubeconform (schema) + policy checks (yq/jq).
# Usage: scripts/k8s-validate.sh [--self-test] [--require-sealed] [overlay...]
#   --require-sealed: also fail when a `required` row of deploy/k8s/overlays/prod/secrets.tsv has no SealedSecret (go-live).
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
  # hostpath-root: a hostPath volume may only be the pod log directories, mounted read-only by every container that
  # mounts it (any other host path, e.g. /, /var, /etc or k3s state, with an API token could read every Secret)
  (if ($ps | type) == "object" then ($ps.volumes // [])[] | select(.hostPath != null) | . as $v | ($v.hostPath.path // "") as $p
    | if ([("/var/log/pods", "/var/log/containers") as $ok | ($p == $ok or ($p | startswith($ok + "/")))] | any) | not
      then "POLICY: hostpath-root \($o|id): volume \($v.name) mounts host path \"\($p)\" (allowed: /var/log/pods, /var/log/containers, read-only)"
      else ($all[] | . as $c | ($c.volumeMounts // [])[] | select(.name == $v.name and .readOnly != true)
        | fail("hostpath-root"; $o; $c; "mounts hostPath volume \($v.name) (\($p)) without readOnly: true")) end
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
| select(.name != "wait-for-postgres" or ((.image // "") | startswith("postgres:17-alpine@sha256:") | not)) # the pinned DB-gate init is the only non-app image
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

# db-gate: every Deployment in ns apps whose containers take DB settings from a Secret of this build (envFrom of a Secret that
# has a DB_* key, or a secretKeyRef to a DB_* key) must have an initContainer named wait-for-postgres (a DB pod must not
# crash-loop while Postgres starts). A secretKeyRef to a non-DB key of such a Secret (web -> SEPAY_WEBHOOK_SECRET) does not count.
JQ_DBGATE="$JQ_DEFS"'
(map(select(type == "object" and .kind == "Secret")
     | {key: "\(ns)/\(.metadata.name)", value: (((.data // {}) + (.stringData // {})) | keys | map(select(startswith("DB_"))) | length > 0)}) | from_entries) as $dbsecrets
| .[] | select(type == "object" and .kind == "Deployment" and ns == "apps") as $o
| (($o | podspec) | (.initContainers // []) | map(.name) | index("wait-for-postgres")) as $gate
| ([(($o | podspec) | (.containers // [])[]
      | ((.envFrom // [])[] | select(.secretRef != null) | "\($o | ns)/\(.secretRef.name)"),
        ((.env // [])[] | select(.valueFrom.secretKeyRef != null and ((.valueFrom.secretKeyRef.key // "") | startswith("DB_")))
         | "\($o | ns)/\(.valueFrom.secretKeyRef.name)"))]
   | map(select($dbsecrets[.] == true)) | unique) as $dbrefs
| select(($dbrefs | length) > 0 and $gate == null)
| "POLICY: db-gate \($o | id): references DB secret \($dbrefs | join(", ")) but has no initContainer wait-for-postgres"'

# --- Prod inventory checks. $rows = secrets.tsv as [{name, ns, req, keys}] (tsv_json). ---

# prod-sealed: every SealedSecret is a secrets.tsv row (name + namespace) and its encryptedData keys equal the row's keys.
JQ_PRODSEALED="$JQ_DEFS"'
($rows | map({key: "\(.ns)/\(.name)", value: .}) | from_entries) as $r
| .[] | select(type == "object" and .kind == "SealedSecret") as $o
| "\($o | ns)/\($o.metadata.name)" as $k
| if $r[$k] == null then "POLICY: prod-sealed \($o | id): \($k) is not a row of secrets.tsv"
  else (($o.spec.encryptedData // {}) | keys | sort) as $have | ($r[$k].keys | sort) as $want
  | if $have != $want then "POLICY: prod-sealed \($o | id): encryptedData keys [\($have | join(","))] != secrets.tsv keys [\($want | join(","))]"
    else empty end
  end'

# --require-sealed: every `required` row has a SealedSecret in the build.
JQ_REQUIRESEALED="$JQ_DEFS"'
[.[] | select(type == "object" and .kind == "SealedSecret") | "\(ns)/\(.metadata.name)"] as $have
| $rows[] | select(.req == "required") | select(("\(.ns)/\(.name)") as $k | $have | index($k) | not)
| "POLICY: prod-sealed \(.name) (ns \(.ns)): required secret has no SealedSecret (scripts/seal-secrets.sh)"'

# secrets-manifest: every non-optional secretRef / secretKeyRef names a secrets.tsv row in the same namespace,
# and every referenced key is one of the row's keys.
JQ_SECRETSMANIFEST="$JQ_DEFS"'
($rows | map({key: .name, value: .}) | from_entries) as $r
| .[] | select(type == "object" and (.kind | IN("Pod","Deployment","StatefulSet","DaemonSet","Job","CronJob","ReplicaSet"))) as $o
| (($o | podspec) | (.containers // []) + (.initContainers // []))[] as $c
| ( ($c.envFrom // [])[] | select(.secretRef != null and (.secretRef.optional | not)) | .secretRef.name as $n
    | if $r[$n] == null then "POLICY: secrets-manifest \($o | id): container \($c.name): secretRef \($n) is not a row of secrets.tsv"
      elif $r[$n].ns != ($o | ns) then "POLICY: secrets-manifest \($o | id): container \($c.name): secretRef \($n) is in ns \($r[$n].ns) in secrets.tsv, not \($o | ns)"
      else empty end ),
  ( ($c.env // [])[] | select(.valueFrom.secretKeyRef != null and (.valueFrom.secretKeyRef.optional | not))
    | .valueFrom.secretKeyRef as $ref | $ref.name as $n
    | if $r[$n] == null then "POLICY: secrets-manifest \($o | id): container \($c.name): secretKeyRef \($n) is not a row of secrets.tsv"
      elif $r[$n].ns != ($o | ns) then "POLICY: secrets-manifest \($o | id): container \($c.name): secretKeyRef \($n) is in ns \($r[$n].ns) in secrets.tsv, not \($o | ns)"
      elif ($r[$n].keys | index($ref.key)) == null then "POLICY: secrets-manifest \($o | id): container \($c.name): secretKeyRef \($n) key \($ref.key) is not in its secrets.tsv row"
      else empty end )'

# prod-domain: Ingress hosts (rules and tls), IngressRoute Host(`...`) matchers, the host of WEB_ORIGIN / WEB_BASE_URL, the domain of MAIL_FROM and the
# cloudflared --http-host-header value all equal $d (APP_DOMAIN). With $strict (the real overlay), the Ingress,
# WEB_ORIGIN, WEB_BASE_URL and MAIL_FROM must exist, so a renamed field cannot silently escape the replacement.
JQ_PRODDOMAIN="$JQ_DEFS"'
def hostof: (split("/") | .[2] // "");
[ .[] | select(type == "object") as $o
  | ( (if $o.kind == "Ingress" then
        (($o.spec.rules // [])[] | {what: "Ingress", where: ($o | id), field: "rules[].host", got: (.host // "")}),
        (($o.spec.tls // [])[] | (.hosts // [])[] | {what: "Ingress", where: ($o | id), field: "tls[].hosts[]", got: .})
      else empty end),
      (if $o.kind == "IngressRoute" then
        (($o.spec.routes // [])[] | (.match // "") as $m
         | if ($m | test("Host\\(`[^`]*`\\)")) then ($m | [scan("Host\\(`([^`]*)`\\)")[0]][] | {what: "IngressRoute", where: ($o | id), field: "routes[].match Host", got: .})
           else {what: "IngressRoute", where: ($o | id), field: "routes[].match Host", got: "(no Host matcher)"} end)
      else empty end),
      (if ($o.kind | IN("Pod","Deployment","StatefulSet","DaemonSet","Job","CronJob","ReplicaSet")) then
        (($o | podspec) | (.containers // []) + (.initContainers // []))[] as $c
        | ( (($c.env // [])[] | select(.name == "WEB_ORIGIN" or .name == "WEB_BASE_URL")
             | {what: .name, where: ($o | id), field: "env \(.name)", got: ((.value // "") | hostof)}),
            (($c.env // [])[] | select(.name == "MAIL_FROM")
             | {what: .name, where: ($o | id), field: "env MAIL_FROM domain", got: ((.value // "") | split("@") | .[1] // "")}),
            ($c.args // [] | to_entries[]
             | if .value == "--http-host-header" then {what: "host-header", where: ($o | id), field: "args --http-host-header", got: ($c.args[.key + 1] // "")}
               elif (.value | type) == "string" and (.value | startswith("--http-host-header=")) then {what: "host-header", where: ($o | id), field: "args --http-host-header=", got: (.value | sub("^--http-host-header="; ""))}
               else empty end) )
      else empty end) )
] as $found
| ($found[] | select(.got != $d) | "POLICY: prod-domain \(.where): \(.field) is \"\(.got)\", not APP_DOMAIN \"\($d)\""),
  (if $strict then (["Ingress", "WEB_ORIGIN", "WEB_BASE_URL", "MAIL_FROM"][] as $w
     | select(($found | map(.what) | index($w)) == null)
     | "POLICY: prod-domain build: no \($w) found (APP_DOMAIN replacement target missing)") else empty end)'

# secrets.tsv -> JSON rows; fails on a malformed row. The `?` (value may be empty) key marker is stripped.
tsv_json() { # $1 = secrets.tsv
  tr -d '\r' <"$1" | jq -R -s '[split("\n")[] | select(length > 0 and (startswith("#") | not)) | split("\t")
    | if length != 4 or (.[2] | IN("required","optional") | not) or .[3] == "" then error("malformed secrets.tsv row: \(.[0])")
      else {name: .[0], ns: .[1], req: .[2], keys: (.[3] | split(",") | map(rtrimstr("?")))} end]'
}

# prod-protected: Argo CD must never delete a Namespace or an Argo-managed PersistentVolumeClaim (finalizer or prune):
# each carries argocd.argoproj.io/sync-options with Delete=false and Prune=false. StatefulSet volumeClaimTemplates PVCs
# are not Argo-managed; their StatefulSet must state persistentVolumeClaimRetentionPolicy Retain/Retain explicitly.
JQ_PRODPROTECTED="$JQ_DEFS"'
.[] | select(type == "object") as $o
| if ($o.kind | IN("Namespace","PersistentVolumeClaim")) then
    ((($o.metadata.annotations // {})["argocd.argoproj.io/sync-options"] // "") | split(",") | map(gsub("\\s"; ""))) as $opts
    | select(($opts | index("Delete=false")) == null or ($opts | index("Prune=false")) == null)
    | "POLICY: prod-protected \($o | id): needs annotation argocd.argoproj.io/sync-options: Delete=false,Prune=false"
  elif $o.kind == "StatefulSet" and (($o.spec.volumeClaimTemplates // []) | length) > 0 then
    select(($o.spec.persistentVolumeClaimRetentionPolicy.whenDeleted // "") != "Retain" or ($o.spec.persistentVolumeClaimRetentionPolicy.whenScaled // "") != "Retain")
    | "POLICY: prod-protected \($o | id): volumeClaimTemplates need persistentVolumeClaimRetentionPolicy {whenDeleted: Retain, whenScaled: Retain}"
  else empty end'

# prod-ingress: the public surface is an allowlist. Every Ingress is class traefik with no defaultBackend; every path is
# web:3000 (Prefix) or exactly payment-svc:8085 Exact /webhooks/sepay (payment-svc also serves /webhooks/paypal, /admin/*,
# /orders*). Every Ingress carries a rateLimit Middleware of this build keyed on Cf-Connecting-IP (behind cloudflared an
# IP criterion would bucket every client together), and the payment-svc route a buffering body cap <= 64 KiB. IngressRoutes:
# web:3000 only, each route rate limited the same way; no IngressRouteTCP/UDP, LoadBalancer, NodePort or externalIPs.
JQ_PRODINGRESS="$JQ_DEFS"'
. as $all | (map(select(type == "object" and .kind == "Middleware")) | map({key: "\(ns)-\(.metadata.name)@kubernetescrd", value: (.spec // {})}) | from_entries) as $mw
| ( $all[] | select(type == "object" and .kind == "Ingress") as $o
| ((($o.metadata.annotations // {})["traefik.ingress.kubernetes.io/router.middlewares"] // "") | split(",") | map(gsub("\\s"; "")) | map(select(length > 0))) as $refs
| [$refs[] | $mw[.] | select(. != null)] as $specs
| [($o.spec.rules // [])[] | (.http.paths // [])[] | {path: (.path // ""), type: (.pathType // ""), svc: (.backend.service.name // ""), port: (.backend.service.port.number // .backend.service.port.name // "")}] as $paths
| ( (if ($o.spec.ingressClassName // "") != "traefik" then "POLICY: prod-ingress \($o | id): ingressClassName must be traefik" else empty end),
    (if $o.spec.defaultBackend != null then "POLICY: prod-ingress \($o | id): defaultBackend is not allowed" else empty end),
    (if ($paths | length) == 0 then "POLICY: prod-ingress \($o | id): no rule paths" else empty end),
    ($paths[] | select(((.svc == "web" and .port == 3000 and .type == "Prefix")
                        or (.svc == "payment-svc" and .port == 8085 and .type == "Exact" and .path == "/webhooks/sepay")) | not)
      | "POLICY: prod-ingress \($o | id): path \(.path) (\(.type)) -> \(.svc):\(.port) is not allowed (only web:3000 Prefix, or payment-svc:8085 Exact /webhooks/sepay)"),
    ($refs[] | select($mw[.] == null) | "POLICY: prod-ingress \($o | id): middleware \(.) is not a Middleware of this build"),
    (if ([$specs[] | select(.rateLimit != null and (.rateLimit.sourceCriterion.requestHeaderName // "") == "Cf-Connecting-IP")] | length) == 0
       then "POLICY: prod-ingress \($o | id): needs a rateLimit Middleware with sourceCriterion.requestHeaderName Cf-Connecting-IP (annotation traefik.ingress.kubernetes.io/router.middlewares)" else empty end),
    (if ([$paths[] | select(.svc == "payment-svc")] | length) > 0
        and ([$specs[] | select((.buffering.maxRequestBodyBytes // 0) > 0 and .buffering.maxRequestBodyBytes <= 65536)] | length) == 0
       then "POLICY: prod-ingress \($o | id): the payment-svc route needs a buffering Middleware with 0 < maxRequestBodyBytes <= 65536" else empty end)
  )),
  # Traefik IngressRoute (header/method matches a plain Ingress cannot express): web:3000 only, each route behind a
  # Cf-Connecting-IP rateLimit Middleware of this build (same namespace unless stated). TCP/UDP routes and Services
  # reachable from outside the cluster (LoadBalancer, NodePort, externalIPs) are never part of the public surface.
  ( $all[] | select(type == "object" and .kind == "IngressRoute") as $o
    | ($o.spec.routes // []) as $routes
    | (if ($routes | length) == 0 then "POLICY: prod-ingress \($o | id): no routes" else empty end),
      ($routes | to_entries[] | .key as $i | .value as $r
       | ([($r.middlewares // [])[] | "\(.namespace // ($o | ns))-\(.name)@kubernetescrd"]) as $refs
       | ( (if ($r.kind // "") != "Rule" then "POLICY: prod-ingress \($o | id): route \($i) kind must be Rule" else empty end),
           (if (($r.services // []) | length) == 0 then "POLICY: prod-ingress \($o | id): route \($i) has no services" else empty end),
           (($r.services // [])[] | select(((.name // "") == "web" and (.port // "") == 3000 and ((.kind // "Service") == "Service")) | not)
            | "POLICY: prod-ingress \($o | id): route \($i) service \(.name // ""):\(.port // "") is not allowed (only web:3000)"),
           ($refs[] | select($mw[.] == null) | "POLICY: prod-ingress \($o | id): route \($i) middleware \(.) is not a Middleware of this build"),
           (if ([$refs[] | $mw[.] | select(. != null and .rateLimit != null and (.rateLimit.sourceCriterion.requestHeaderName // "") == "Cf-Connecting-IP")] | length) == 0
              then "POLICY: prod-ingress \($o | id): route \($i) needs a rateLimit Middleware with sourceCriterion.requestHeaderName Cf-Connecting-IP" else empty end) ) ) ),
  ( $all[] | select(type == "object") as $o
    | if ($o.kind | IN("IngressRouteTCP", "IngressRouteUDP")) then "POLICY: prod-ingress \($o | id): TCP/UDP routes are not allowed"
      elif $o.kind == "Service" and (($o.spec.type // "ClusterIP") | IN("LoadBalancer", "NodePort")) then "POLICY: prod-ingress \($o | id): type \($o.spec.type) is not allowed (ClusterIP only; the public entry is cloudflared)"
      elif $o.kind == "Service" and (($o.spec.externalIPs // []) | length) > 0 then "POLICY: prod-ingress \($o | id): externalIPs are not allowed"
      else empty end )'

# prod-backup: a sealed ops-secrets (ns data) <=> every BACKUP_REQUIRED of the pg-backup CronJob is "1". Sealed but 0:
# a broken S3 config would skip silently with real data at stake; 1 without the secret: every nightly backup fails.
JQ_PRODBACKUP="$JQ_DEFS"'
([.[] | select(type == "object" and .kind == "SealedSecret" and .metadata.name == "ops-secrets" and ns == "data")] | length > 0) as $sealed
| [.[] | select(type == "object" and .kind == "CronJob" and .metadata.name == "pg-backup") as $o
   | (($o | podspec) | (.containers // []) + (.initContainers // []))[] as $c
   | ($c.env // [])[] | select(.name == "BACKUP_REQUIRED") | {c: $c.name, v: (.value // "")}] as $req
| if $sealed and ($req | length) == 0 then "POLICY: prod-backup CronJob/pg-backup: ops-secrets is sealed but no pg-backup BACKUP_REQUIRED found"
  elif $sealed then ($req[] | select(.v != "1") | "POLICY: prod-backup CronJob/pg-backup: container \(.c): ops-secrets is sealed, so BACKUP_REQUIRED must be \"1\" (got \"\(.v)\")")
  else ($req[] | select(.v == "1") | "POLICY: prod-backup CronJob/pg-backup: container \(.c): BACKUP_REQUIRED is \"1\" but ops-secrets is not sealed (every backup would fail)")
  end'

# Prod inventory inputs: a fixture may carry its own, else the real prod overlay's.
prod_tsv() { if [ -f "$1/secrets.tsv" ]; then echo "$1/secrets.tsv"; else echo deploy/k8s/overlays/prod/secrets.tsv; fi; }
prod_domain_file() {
  if [ -f "$1/domain.yaml" ]; then echo "$1/domain.yaml"
  elif [ -f "$1/domain/domain.yaml" ]; then echo "$1/domain/domain.yaml"
  else echo deploy/k8s/overlays/prod/domain/domain.yaml; fi
}
e2e_secrets_dir() { if [ -d "$1/e2e-secrets" ]; then echo "$1/e2e-secrets"; else echo deploy/k8s/overlays/e2e/secrets; fi; }

# The prod-only inventory checks; $1 = dir, $2 = build as a JSON array. Prints POLICY lines; non-zero on internal errors.
prod_inventory_checks() {
  local dir="$1" json="$2" tsv rows dfile domain strict=false res f name have want
  tsv=$(prod_tsv "$dir")
  rows=$(tsv_json "$tsv" 2>&1) || { echo "POLICY: secrets-manifest $tsv: cannot parse: $rows"; return 1; }
  [ "$(jq 'length' <<<"$rows")" -gt 0 ] || { echo "POLICY: secrets-manifest $tsv: no rows"; return 1; }
  res=$(jq -r --argjson rows "$rows" "$JQ_PRODSEALED" <<<"$json") || { echo "POLICY: internal $dir: prod-sealed check failed"; return 1; }
  [ -n "$res" ] && echo "$res"
  if [ "$REQUIRE_SEALED" = 1 ]; then
    res=$(jq -r --argjson rows "$rows" "$JQ_REQUIRESEALED" <<<"$json") || { echo "POLICY: internal $dir: require-sealed check failed"; return 1; }
    [ -n "$res" ] && echo "$res"
  fi
  res=$(jq -r --argjson rows "$rows" "$JQ_SECRETSMANIFEST" <<<"$json") || { echo "POLICY: internal $dir: secrets-manifest check failed"; return 1; }
  [ -n "$res" ] && echo "$res"
  # every e2e env file has exactly its row's keys (the e2e overlay must exercise the prod secret shapes)
  for f in "$(e2e_secrets_dir "$dir")"/*.env; do
    [ -f "$f" ] || { echo "POLICY: secrets-manifest $dir: no e2e secrets/*.env found"; continue; }
    name=$(basename "$f" .env)
    want=$(jq -r --arg n "$name" '.[] | select(.name == $n) | .keys[]' <<<"$rows" | tr -d '\r' | sort)
    if [ -z "$want" ]; then echo "POLICY: secrets-manifest $f: $name is not a row of secrets.tsv"; continue; fi
    have=$(tr -d '\r' <"$f" | sed -n '/^[[:space:]]*#/d; s/^\([^=[:space:]][^=]*\)=.*/\1/p' | sort)
    [ "$have" = "$want" ] || echo "POLICY: secrets-manifest $f: keys [$(paste -sd, <<<"$have")] != secrets.tsv keys [$(paste -sd, <<<"$want")]"
  done
  dfile=$(prod_domain_file "$dir")
  domain=$(yq -r 'select(.kind == "ConfigMap" and .metadata.name == "app-domain") | .data.APP_DOMAIN // ""' "$dfile" 2>/dev/null | tr -d '\r') \
    || { echo "POLICY: prod-domain $dfile: cannot parse"; return 1; }
  [[ "$domain" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || { echo "POLICY: prod-domain $dfile: APP_DOMAIN \"$domain\" is missing or not a hostname"; return 0; }
  [ "$dfile" = "$dir/domain/domain.yaml" ] && strict=true
  res=$(jq -r --arg d "$domain" --argjson strict "$strict" "$JQ_PRODDOMAIN" <<<"$json") || { echo "POLICY: internal $dir: prod-domain check failed"; return 1; }
  [ -n "$res" ] && echo "$res"
  return 0
}

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
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_PRODPROTECTED" <<<"$json") || { echo "POLICY: internal $1: prod-protected check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_PRODINGRESS" <<<"$json") || { echo "POLICY: internal $1: prod-ingress check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_PRODBACKUP" <<<"$json") || { echo "POLICY: internal $1: prod-backup check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      prod_inventory_checks "$1" "$json" || return 1 ;;
    e2e)
      res=$(jq -r "$JQ_SECRETREFS" <<<"$json") || { echo "POLICY: internal $1: secret-refs check failed"; return 1; }
      [ -n "$res" ] && echo "$res"
      res=$(jq -r "$JQ_DBGATE" <<<"$json") || { echo "POLICY: internal $1: db-gate check failed"; return 1; }
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


# alerts: Grafana alert rules as code. $1 = dir holding alerting/rules.yaml and metric-names.txt. rules.yaml parses, its
# uids are exactly the six of the spec (§10) plus backup-skipped and mail-cap-reached (final review I8, I1), every data[].datasourceUid is `prometheus` or `__expr__`, and every metric
# identifier in an A query is listed in metric-names.txt (same extraction as the dashboards, plus names followed by `[` and `_bytes` names).
# Fails closed: missing files or parse errors fail.
ALERT_UIDS="backup-skipped consumer-lag-rising deploy-failed disk-usage-high error-rate-high latency-p95-high mail-cap-reached pod-restart-loop"
check_alerts() {
  local dir="$1" f="$1/alerting/rules.yaml" rc=0 res m names json uids
  echo "== $dir (alerts)"
  [ -f "$f" ] || { echo "POLICY: alerts $f: missing"; return 1; }
  [ -s "$dir/metric-names.txt" ] || { echo "POLICY: alerts $dir: metric-names.txt missing or empty"; return 1; }
  names=$(tr -d '\r' <"$dir/metric-names.txt" | sed '/^[[:space:]]*$/d')
  json=$(yq -o=json '.' "$f" | jq -c '.') || { echo "POLICY: alerts $f: cannot parse"; return 1; }
  uids=$(jq -r '[.groups[]?.rules[]?.uid] | sort | join(" ")' <<<"$json" | tr -d '\r') || { echo "POLICY: alerts $f: no groups/rules"; return 1; }
  [ "$uids" = "$ALERT_UIDS" ] || { echo "POLICY: alerts $f: rule uids [$uids] != [$ALERT_UIDS]"; rc=1; }
  res=$(jq -r '.groups[]?.rules[]? | . as $r | .data[]? | select((.datasourceUid // "") | IN("prometheus", "__expr__") | not)
    | "POLICY: alerts \($r.uid): query \(.refId) datasourceUid \"\(.datasourceUid // "")\" must be prometheus or __expr__"' <<<"$json" | tr -d '\r') \
    || { echo "POLICY: alerts $f: datasource check failed"; return 1; }
  if [ -n "$res" ]; then echo "$res"; rc=1; fi
  res=$(jq -r --arg re "$METRIC_RE|[a-z_][a-z0-9_]*(?=\[)|[a-z_][a-z0-9_]*_bytes\b" '.groups[]?.rules[]? | . as $r | .data[]? | select(.refId == "A") | .model.expr // empty
    | scan($re) | "\($r.uid)\t\(.)"' <<<"$json" | tr -d '\r' | sort -u) \
    || { echo "POLICY: alerts $f: metric extraction failed"; return 1; }
  while IFS=$'\t' read -r uid m; do
    [ -z "$m" ] && continue
    if ! grep -qxF "$m" <<<"$names"; then echo "POLICY: alerts $f: rule $uid: metric $m is not listed in metric-names.txt"; rc=1; fi
  done <<<"$res"
  return $rc
}

# argocd: every Application under $1 (recursively, *.yaml) has a spec.source.path that exists and builds with
# `kubectl kustomize`, targetRevision main, and an automated prune + selfHeal sync policy. Paths are repo-relative.
# Every path other than deploy/argocd/apps (the root's) must be a slice listed in the prod aggregate
# (deploy/k8s/overlays/prod/kustomization.yaml), so the prod checks (prod-no-secrets, ...) cover everything Argo deploys.
# Fails closed: unparsable files or no Application at all fail.
check_argocd() {
  local dir="$1" f apps n=0 rc=0 name path rev prune heal aggregated
  echo "== $dir (argocd)"
  aggregated=$(yq -r '.resources[]' deploy/k8s/overlays/prod/kustomization.yaml | tr -d '\r' | sed 's|^|deploy/k8s/overlays/prod/|') \
    || { echo "POLICY: argocd deploy/k8s/overlays/prod/kustomization.yaml: cannot parse"; return 1; }
  while IFS= read -r f; do
    apps=$(yq -o=json '.' "$f" | jq -c -s '.[] | select(type == "object" and .kind == "Application")
      | {name: .metadata.name, path: (.spec.source.path // ""), rev: (.spec.source.targetRevision // ""),
         prune: (.spec.syncPolicy.automated.prune // false), heal: (.spec.syncPolicy.automated.selfHeal // false),
         project: (.spec.project // ""),
         protected: ((((.metadata.annotations // {})["argocd.argoproj.io/sync-options"] // "") | split(",") | map(gsub("\\s"; ""))) as $o
                     | ($o | index("Prune=false")) != null and ($o | index("Delete=false")) != null)}') \
      || { echo "POLICY: argocd $f: cannot parse"; rc=1; continue; }
    while IFS= read -r a; do
      [ -z "$a" ] && continue
      n=$((n + 1))
      name=$(jq -r .name <<<"$a" | tr -d '\r'); path=$(jq -r .path <<<"$a" | tr -d '\r'); rev=$(jq -r .rev <<<"$a" | tr -d '\r')
      prune=$(jq -r .prune <<<"$a" | tr -d '\r'); heal=$(jq -r .heal <<<"$a" | tr -d '\r')
      if [ -z "$path" ] || [ ! -d "$path" ]; then echo "POLICY: argocd $f Application/$name: source.path \"$path\" does not exist"; rc=1
      elif ! kubectl kustomize "$path" >/dev/null 2>&1; then echo "POLICY: argocd $f Application/$name: source.path $path does not build with kubectl kustomize"; rc=1; fi
      if [ "$rev" != main ]; then echo "POLICY: argocd $f Application/$name: targetRevision \"$rev\" must be main (only bootstrap overrides it)"; rc=1; fi
      if [ "$prune" != true ] || [ "$heal" != true ]; then echo "POLICY: argocd $f Application/$name: syncPolicy.automated needs prune: true and selfHeal: true"; rc=1; fi
      if [ "$path" != deploy/argocd/apps ] && ! grep -qxF -- "$path" <<<"$aggregated"; then
        echo "POLICY: argocd $f Application/$name: source.path $path is not a slice of the prod aggregate deploy/k8s/overlays/prod/kustomization.yaml"; rc=1
      fi
      # Every child (not the root) is in the prod AppProject (its Secret blacklist), and root never prunes or deletes it:
      # a renamed or removed apps/*.yaml must not cascade-delete a whole prod slice.
      if [ "$path" != deploy/argocd/apps ]; then
        if [ "$(jq -r .project <<<"$a" | tr -d '\r')" != prod ]; then
          echo "POLICY: argocd $f Application/$name: spec.project must be prod (the AppProject that forbids plain Secrets)"; rc=1
        fi
        if [ "$(jq -r .protected <<<"$a" | tr -d '\r')" != true ]; then
          echo "POLICY: argocd $f Application/$name: needs annotation argocd.argoproj.io/sync-options: Prune=false,Delete=false"; rc=1
        fi
      fi
    done <<<"$apps"
  done < <(find "$dir" -name '*.yaml' -type f | sort)
  if [ "$n" -eq 0 ]; then echo "POLICY: argocd $dir: no Application found"; return 1; fi
  return $rc
}

self_test() {
  local rc=0 d name check out
  for d in scripts/testdata/k8s-validate/*/; do
    name=$(basename "$d"); check=$(tr -d '\r\n' <"$d/expect") # `expect` holds the POLICY check the fixture must trigger
    if [ -d "$d/dashboards" ]; then out=$(check_dashboards "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }
    elif [ -d "$d/alerting" ]; then out=$(check_alerts "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }
    elif [ -d "$d/argocd" ]; then out=$(check_argocd "${d%/}/argocd" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }
    else out=$(SKIP_KUBECONFORM=1 validate_one "${d%/}" 2>&1) && { echo "SELF-TEST FAIL: $name was accepted"; rc=1; continue; }; fi
    if grep -q "^POLICY: $check " <<<"$out"; then echo "self-test ok: $name rejected with POLICY: $check"
    else echo "SELF-TEST FAIL: $name rejected without 'POLICY: $check':"; echo "$out"; rc=1; fi
  done
  return $rc
}

REQUIRE_SEALED=0
if [ "${1:-}" = "--self-test" ]; then self_test; exit $?; fi
targets=()
for a in "$@"; do
  if [ "$a" = --require-sealed ]; then REQUIRE_SEALED=1; else targets+=("$a"); fi
done
set -- "${targets[@]+"${targets[@]}"}"
if [ ${#targets[@]} -eq 0 ]; then
  targets=(deploy/k8s/platform)
  for d in deploy/k8s/overlays/*/; do [ -d "$d" ] && targets+=("${d%/}"); done
fi
fail=0
for t in "${targets[@]}"; do validate_one "${t%/}" || fail=1; done
[ $# -eq 0 ] && { check_dashboards deploy/k8s/platform/grafana || fail=1; }
[ $# -eq 0 ] && { check_alerts deploy/k8s/platform/grafana || fail=1; }
[ $# -eq 0 ] && { check_argocd deploy/argocd || fail=1; }
[ $fail -eq 0 ] && echo "k8s-validate: ok" || echo "k8s-validate: FAILED"
exit $fail
