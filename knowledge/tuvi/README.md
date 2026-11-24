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

Unpublishing: never delete a record from the JSONL files. Deleting a line leaves the database row
`approved` and still served. To unpublish, set `status: "hidden"`.

Star keys: adjective stars and ring stars share some keys (`PhiLiem`, `HoaCai`, `HamTri`,
`ThienDuc`), so doctrine under `tuvi:star=<Key>` applies to both placements.
