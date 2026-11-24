# Tử vi knowledge

JSONL files, one record per line, imported by reading-tuvi when `KNOWLEDGE_DIR` points here.
Schema: `contracts/knowledge/tuvi-record.schema.json`. Identity is `(key, school, lang)`; re-importing
is idempotent and only changed records bump their `version`.

- `frames.vi.jsonl`: voice frames (key `tuvi:frame=<id>`), source_ref `original:frame`.
- `daily.vi.jsonl`: "Hôm nay" lines (key `tuvi:daily:area=<A>:level=<L>`), source_ref `original:daily`.

Voice: Lão Thiên Cơ says "ta" and calls the reader "con"; at most two sentences per record.
Placeholders allowed: `{palace}`, `{star}`, `{cuc}`, `{date}`, `{lunar}`, `{canchi}`.
Content rules: culture/entertainment tone; no fear, no death/illness/disaster predictions, no
remedies or paid offers; health lines are never medical advice. Only `approved` records are served.
