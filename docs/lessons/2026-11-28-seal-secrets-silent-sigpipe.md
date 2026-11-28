# seal-secrets.sh died silently with exit 141

- Date: 2026-11-28
- Tags: infra | process

## Symptom
`scripts/seal-secrets.sh --cert …` printed nothing and exited 141 on every run when resealing `reading-tuvi-secrets` to
add `OPENROUTER_API_KEY`, so the SealedSecret kept the old key set and `k8s-validate --require-sealed` failed.

## Root cause
`header=$(tr -d '\r' <"$kfile" | sed -n '/^[^#]/q; p')`: sed quits at the first non-comment line, tr gets SIGPIPE, and
`set -e -o pipefail` ends the script without any message. Two more producer | `grep -q` pipes had the same shape. This
is the CLAUDE.md shell rule ("never pipe a producer into an early-closing reader") broken in a script that predates it.

## Fix
Capture first, then feed the reader a here-string (three places). An ERR trap with `$LINENO` in a throwaway copy found the
line without tracing values (xtrace would have printed secret values).

## How to catch earlier
A grep over `scripts/` and `infra/` for `| *(sed -n .*q|head|grep -q|grep -m)` would flag these. Worth a CI check if
it happens again.

## Escalation
none (the rule exists: second occurrence of the class after 2026-11-26-bootstrap-sigpipe; the next one gets the grep check)
