# Grafana alert annotations sent raw template text (`$$labels`)

- Date: 2026-11-28
- Tags: infra

## Symptom
Telegram alerts were hard to read: the `summary` showed the raw template `{{ $$labels.k8s_pod_name }} restarted 3+ times
in 15m` instead of the pod name. It came inside Grafana's default label, annotation and URL dump.

## Root cause
`rules.yaml` escaped `$` as `$$` on the belief that Grafana expands env vars in alerting provisioning files. Grafana
13.3 (otel-lgtm 0.41.1) stores alert rule annotations verbatim, so the annotation template `{{ $$labels.x }}` fails and
Grafana sends the text unrendered. Nothing rendered an annotation before prod: the promtool tests cover only the
expressions, and the kind e2e checks only that the rules are healthy.

## Fix
Write `$labels` with one `$`. Every rule has Vietnamese `summary` and `service` annotations, and the contact point uses
a short template (`telegram.vi`). The `grafana-alerting` ConfigMap now has a content-hash name, so a rule or template
change restarts lgtm (its init container renders the files only at pod start).

## How to catch earlier
Render a real firing alert in a throwaway lgtm container (provision the files, add an always-firing rule, read
`/api/alertmanager/grafana/api/v2/alerts`, and POST to `/api/alertmanager/grafana/config/api/v1/templates/test`)
whenever annotations or templates change.

## Escalation
check: `scripts/k8s-validate.sh` `check_alerts` rejects `$$` in any annotation and requires `summary` + `service` on
every rule (self-test fixture `scripts/testdata/k8s-validate/bad-alert-annotation`).
