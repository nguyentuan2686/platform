# Rules on main changed under a long-running branch

- Date: 2026-11-28
- Tags: process

## Symptom
PR #62 (astro-chart-svc, F2.1) failed CI job `system-docs` on its first run: it changes `contracts/`,
`deploy/k8s/` and `.github/workflows/` but not `docs/system/index.html`.

## Root cause
The branch started before the system-docs rule and its hard check landed on main. The rule arrived with
the merge of `origin/main` into the branch, and the merge resolved cleanly without anyone re-reading the
merged CLAUDE.md before pushing.

## Fix
Added astro-chart-svc to the status table and one dated line to "Lịch sử" in `docs/system/index.html`;
ran `scripts/check-system-docs.sh` locally (OK) before pushing.

## How to catch earlier
After merging `main` into a branch that lived for hours, read `git diff <old-base>..origin/main -- CLAUDE.md`
and run the cheap PR checks locally (`scripts/check-system-docs.sh` with the branch's changed files) before pushing.

## Escalation
none (first occurrence)
