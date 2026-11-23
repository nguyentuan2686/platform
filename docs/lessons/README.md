# Lessons

One file per incident: `YYYY-MM-DD-<slug>.md`. Created by the `retro` step after any CI/test/deploy failure or prod bug.
See the escalation ladder in `CLAUDE.md`.

## Template

```markdown
# <title>

- Date: YYYY-MM-DD
- Tags: build | test | design | infra | process

## Symptom
What was observed.

## Root cause
Why it really happened.

## Fix
What was changed.

## How to catch earlier
Which check, test or habit would have caught it sooner.

## Escalation
none | rule | check
(rule = added to CLAUDE.md or a skill; check = lint/ArchUnit/CI/hook, with link)
```
<!-- throwaway docs-only change to verify ci-gate; reverted next commit -->
