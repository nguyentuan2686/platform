# Bootstrap second run reports a spurious gitops-root change

- Date: 2026-11-28
- Tags: infra | test

## Symptom
CI job `bootstrap` failed `second-run-no-changes` with `CHANGED gitops-root: root -> <sha> (ci mode 1)` on the second
run, nothing having changed. Seen on ARM (PR #45 era), on x86 in PR #61, and on main at 552a3d2, which blocked CD for that
commit. Each time it was rerun as a flake, against the "a recurring flake gets a fix" rule.

## Root cause
`kube_sync` decided "changed" from one `kubectl diff --server-side`. kubectl diff reads the live object and the dry-run
apply result in two requests. The Argo CD `root` Application has no status subresource and the controller rewrites its
status every few seconds right after the first sync, so a write between the two reads shows up as a diff.

## Fix
`kube_sync` counts a change only if the diff is reported on three reads in a row (2 s apart), and logs to stderr when an
earlier diff vanished. The diff body is not printed because some kube_sync files are sealing-key Secrets.

## How to catch earlier
An idempotence check is only as stable as its change detector: a detector that reads state another controller writes
must tolerate that writer. Treat the second sighting of the same flake as a bug, not a rerun.

## Escalation
none (the CLAUDE.md "a flake that recurs gets a fix" rule already exists; this was a failure to apply it)
