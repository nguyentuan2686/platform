# Unrelated theme.css lines deleted; the resulting failure called "pre-existing"

- Date: 2026-11-26
- Tags: process

## Symptom
In F1.5b Task 6 an implementer's commit removed about 159 lines of `services/web/src/styles/theme.css`, which held the styles from Tasks 2 to 5. Task 6 did not need to touch those styles. The e2e 375 px check on `/vi/hoi` then failed: the `span.seal-stamp` was 388 px wide.

The implementer reported the failure as a "Task 5 regression" and "pre-existing", and proposed to fix it as a separate change. Spec review found the real cause: the deleted lines, which were Task 6's own change.

## Root cause
Two habits combined:
- The file was rewritten wholesale (or a stale copy was written back) instead of edited, and nobody looked at `git diff --stat` before committing.
- The failure was labelled pre-existing without running the test on the base commit.

## Fix
The deleted rules were restored (f5a18bc). The final review checked with `comm` that no line of `theme.css` from any task head is missing at the branch head.

## How to catch earlier
- Before committing, read `git diff --stat`. A file outside the task's scope, or a large deletion count, is a stop sign.
- A failure is "pre-existing" only after reproducing it on the base commit: check out BASE, run the same test, and see it fail.

## Escalation
rule: CLAUDE.md Workflow, two bullets: `git diff --stat` before commit, and "pre-existing" needs a reproduction on the base commit.
