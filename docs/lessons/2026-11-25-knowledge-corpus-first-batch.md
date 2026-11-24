# First doctrine batch broke a hard-coded seed count; banned-substring rule hit "ấm cúng"

- Date: 2026-11-25
- Tags: test

## Symptom
1. Adding the first doctrine records failed `JsonlKnowledgeImporterIT` (expected 24 inserted, got 86).
2. A Tứ Hóa record failed `KnowledgeQualityTest` with `rule=banned`: the phrase "ấm cúng" (cozy) contains the banned substring `cúng`.

## Root cause
1. The IT asserted the exact number of seed records in `knowledge/tuvi`, a number that grows with every corpus batch.
2. Banned words are matched as plain substrings, so harmless Vietnamese words that contain them ("ấm cúng", "yểu điệu", "yểm trợ") are rejected.

## Fix
1. The IT now counts non-blank JSONL lines under `knowledge/tuvi` and asserts the importer matches that number.
2. Reworded to "ấm áp". The substring rule stays strict on purpose (false positives are cheap, misses are not).

## How to catch earlier
Run the full module `verify` (not only the unit tests) on the first content batch; when writing, avoid "ấm cúng", "yểu điệu", "yểm trợ".

## Escalation
none (first occurrence)
