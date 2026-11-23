---
name: retro
description: Use after any CI failure, unexpected test failure, failed deploy, or bug found after merge, to record a lesson and escalate it per the CLAUDE.md ladder.
---

# Retro

Trigger: CI failure, unexpected test failure, deploy failure, or a bug found after merge. Do it once the failure is understood (root cause, not just symptom).

## Steps
1. **Write the lesson**: `docs/lessons/YYYY-MM-DD-<slug>.md` using the template in `docs/lessons/README.md` (Date, Tags, Symptom, Root cause, Fix, How to catch earlier, Escalation). Tags: `build | test | design | infra | process`.
2. **Search for repeats**: `grep -ril "<tag or root-cause keyword>" docs/lessons` and compare root causes.
3. **Escalate by the ladder** (CLAUDE.md "Lessons loop"):
   - First occurrence: lesson file only; `Escalation: none`.
   - Two or more occurrences of the same root cause: add a rule to `CLAUDE.md` or the relevant skill under `.claude/skills/`; `Escalation: rule`.
   - Mechanically checkable: also implement a hard check in the same PR (lint rule, ArchUnit test, CI step, or hook) and link it; `Escalation: check`.
4. **Commit** as `docs(lessons): <summary>` (include the rule/check in the same PR if any).
5. Prune or merge near-duplicate lessons; review `docs/lessons/` after each large feature.

## Rules
- Blameless and concrete: name the file/command/config, not "be more careful".
- Never put secrets, owner email/phone, or personal data in a lesson.
- Owner's personal preferences belong in Claude memory, not in lessons or CLAUDE.md.
