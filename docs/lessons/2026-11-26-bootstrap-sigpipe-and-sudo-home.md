# Bootstrap CI: SIGPIPE under pipefail, and `sudo -E` leaking HOME

- Date: 2026-11-26
- Tags: infra | build

## Symptom
In run 45857609982 on PR #32:
- The x86 leg's second bootstrap run exited 141 right after its first log line.
- The ARM leg passed `verify.sh`, then failed `sealed-key-restored` with
  `error loading config file "/home/runner/.kube/config": permission denied`.

## Root cause
1. `installed="$(k3s --version | head -n1)"` runs under `set -o pipefail`. `head` exits after one line, and k3s can
   still be writing, so it dies of SIGPIPE (exit 141). The pipeline fails and `set -e` ends the script. This is a
   race: it hit one leg out of two. `tailscale version | head` and `cmd | grep -q` had the same shape.
2. The workflow ran `sudo -E ... bootstrap.sh`. `-E` keeps `HOME=/home/runner`, so root's kubectl (inside
   bootstrap) created `/home/runner/.kube` root-owned. The later `mkdir -p ~/.kube` was a no-op, and the runner user
   could not traverse the directory.

## Fix
- Capture the whole output and cut it in the shell (`${v%%$'\n'*}`, `grep -q <<<"$v"`). No early-closing reader
  sits in a pipeline anywhere in `infra/bootstrap`.
- `sudo VAR=... bootstrap.sh` without `-E` (the variables are passed explicitly).
- The runner's kubeconfig lives at `$RUNNER_TEMP/kubeconfig` (exported as `KUBECONFIG`), not under `~/.kube`.

## How to catch earlier
Under `pipefail`, never pipe a producer into `head`, `grep -q` or `grep -m`. Capture the output first.
Never use `sudo -E` for scripts that run kubectl or other tools that write to `$HOME`.

## Escalation
none (first occurrence)
