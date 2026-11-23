---
name: ship
description: Use when a feature branch is ready to integrate; pushes the branch, opens a PR, waits for CI, runs retro on failure, and squash-merges on green.
---

# Ship

Precondition: local verification (`mvn -q verify` or the relevant build) was run and its output read; work committed on a short-lived branch (never `main`).

## gh binary
If `gh` is not on PATH (Windows), use:
```bash
GH=gh; command -v gh >/dev/null 2>&1 || GH="/c/Program Files/GitHub CLI/gh.exe"
```
and call `"$GH"` below.

## Steps
1. `git push -u origin "$(git branch --show-current)"` (never `--no-verify`, never push to `main`).
2. `"$GH" pr create --fill` (edit title/body if `--fill` is poor; conventional-commit title).
3. `"$GH" pr checks --watch` (ci-gate must be green).
4. **Red**: invoke the `retro` skill, find the root cause (`"$GH" run view <id> --log-failed`), fix, commit, push, back to step 3. Do not merge on red, do not bypass checks.
5. **Green** (ci-gate passed): `"$GH" pr merge --squash --delete-branch`. Only this merge form.
6. `git switch main && git pull`.
7. If the change deploys, run the verify-deploy step (`scripts/verify-deploy`) and report evidence; a failed deploy triggers `retro`.

## Rules
- No human review is required when CI is green; CD bot is the only other writer to `main`.
- Report the PR URL and merge result to the owner.
