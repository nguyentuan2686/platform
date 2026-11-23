# CI gate drill: deliberately failing test must block merge

- Date: 2026-11-24
- Tags: process, ci

## Symptom
Drill PR #5 ("DRILL: prove ci-gate blocks merge") changed one assertion in `OrderTest` (total expected `99.99` instead of `25.50`). `order-svc / verify` failed, downstream image jobs were skipped, and the required check `ci-gate` failed. `gh pr merge 5 --squash` was refused: "the base branch policy prohibits the merge" (PR state OPEN / BLOCKED).

## Root cause
Deliberate: a drill to prove branch protection (required check `ci-gate`, strict) works end to end, and to dry-run the retro loop.

## Fix
None needed for code. The drill PR was closed unmerged and its branch deleted.

## How to catch earlier
The gate caught it: `ci-gate` aggregates `needs.*.result` and fails on any failure or cancellation, and it is the only required context, so new services need no protection changes. Re-run the drill after changing `ci.yml` job names or the protection config.

## Escalation
none
