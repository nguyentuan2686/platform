# verify-deploy parsed kubectl stderr as JSON

- Date: 2026-11-27
- Tags: infra | test

## Symptom
In run 45861392787 on PR #32, both bootstrap legs printed `jq: parse error: Invalid literal at line 1, column 6`.
The step then failed with `FAIL argo-<app>: sync= health= revision=` for every app. The diagnostics showed Argo CD had
in fact reached the target: root and prod-platform were Synced/Healthy at the PR sha, and prod-apps/prod-tunnel were
OutOfSync with no ComparisonError.

## Root cause
`state()` captured `kubectl ... -o json 2>&1`. The runner's kubectl is the k3s multi-call binary, which prints
`level=warning msg="open /etc/rancher/k3s/config.yaml: permission denied"` on stderr. That line was prepended to the
JSON, and jq rejected the whole thing.

## Fix
`scripts/verify-deploy.sh` reads stdout only (stderr goes to a temp file and is used only in failure messages). A jq
failure is reported as `unparseable kubectl output`. New test `stderr_warning_does_not_break_parsing`, in which the
fake kubectl warns on stderr.

## How to catch earlier
Never merge stderr into data meant for a parser (`2>&1` into jq/yq). Offline fakes should emit realistic stderr noise.

## Escalation
none (first occurrence)
