# A `[skip ci]` quoted in a commit body skipped the whole PR run

- Date: 2026-11-27
- Tags: process

## Symptom
After pushing the Task 7 (CD workflow) commit to PR #32, no `ci` or `codeql` run started for it.

## Root cause
The commit body described the CD commit format and quoted the marker literally ("with [skip ci]"). GitHub skips
`push` and `pull_request` workflows when the head commit message contains `[skip ci]`, `[ci skip]`, `[no ci]`,
`[skip actions]` or `[actions skip]` anywhere, body included. A squash-merge message that kept the text would also
skip the push run on `main`: no images published, so CD would deploy nothing for that merge.

## Fix
Reworded the commit ("the CI-skip marker") and force-pushed the PR branch with a lease; the run started.

## How to catch earlier
Never write a literal CI-skip marker in a commit message, PR title or PR body (they feed the squash message). Only
the CD bot's own commits carry it. If no run appears within a minute of a push, check the head commit message first.

## Escalation
none (first occurrence)
