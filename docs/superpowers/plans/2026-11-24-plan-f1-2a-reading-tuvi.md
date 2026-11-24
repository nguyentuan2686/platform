# Plan F1.2a — reading-tuvi: luận cơ bản + "Hôm nay" (không LLM) và sao phụ / Tuần–Triệt cho chart-svc

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From a birth (date-time, zone, gender, calendar), a new Java service `reading-tuvi` returns a free basic reading and a free "Hôm nay" summary in Lão Thiên Cơ's voice, assembled only from approved knowledge records looked up by structured keys — no LLM — on top of a chart-svc enriched with Tuần/Triệt, adjective stars, the four 12-god rings and tiểu hạn ages, cross-checked against a Vietnamese tử vi tool.

**Architecture:** chart-svc (Rust, F1.1) gains additive fields only. `reading-tuvi` is a hexagonal Spring Boot service like `order-svc`: `domain` (pure: chart model, key derivation, reading assembly, "Hôm nay" scoring), `application` (use cases + ports `ChartPort`, `KnowledgePort`, `ReadingCachePort`), `adapter` (REST in, RestClient to chart-svc, JDBC/Postgres out, JSONL knowledge importer). Knowledge lives in the repo as JSONL under `knowledge/tuvi/` (reviewable in Git), is imported idempotently into Postgres at startup, and is served from Postgres. F1.2b fills the doctrine records; this plan ships the schema, importer, voice frames and "Hôm nay" lines.

**Tech Stack:** Java 21, Spring Boot (parent version), JDBC + Flyway + Postgres, `RestClient`, Testcontainers, ArchUnit, JaCoCo; Rust chart-svc + `x-iztro` 0.9.0; Node 22 (Docker) for iztro fixtures; Python 3.12 (Docker) for the `lasotuvi` cross-check.

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` — §6.3 (chart-svc), §6.4 (knowledge record, basic reading), §11 (Vietnamese school + ~50-chart cross-check), §13–§14 (Hôm nay, Lão Thiên Cơ, voice frames), §15 (no fear-based content, no giải hạn). Platform rules: `CLAUDE.md`, `.claude/skills/java-service-conventions/SKILL.md`, `.claude/skills/rust-service-conventions/SKILL.md`. Patterns to copy: `services/order-svc` (Java), `services/chart-svc` (Rust, F1.1).

## Global Constraints

- Platform contract as `CLAUDE.md`: JSON logs with `timestamp, level, service, trace_id, span_id, message`; `/health/live`, `/health/ready`; errors `application/problem+json` with `trace_id`; 400 slug `validation-error` with `errors: [{field, message}]`; 500 detail exactly `"Unexpected error"`.
- Java: base package `dev.platform.reading.tuvi`; hexagonal `domain`/`application`/`adapter`; `domain` has no Spring/Jackson/JDBC imports (ArchUnit); constructor injection; records for DTOs/value objects; ≥ 80% line coverage on `domain` + `application`.
- reading-tuvi config via env: `PORT` (default `8083`), `DB_URL`, `DB_USER`, `DB_PASSWORD`, `CHART_SVC_URL` (default `http://localhost:8082`), `KNOWLEDGE_DIR` (unset → no import). Database `reading_tuvi`, owner `reading`.
- **Privacy:** no birth date/time, zone or gender in any log line or error detail; cache keys are hashes of the chart, never of the birth.
- **No LLM** anywhere in this plan. Text shown to users comes only from knowledge records (doctrine, F1.2b) or voice frames / daily lines (this plan). Missing doctrine → the `missing` frame, never an error and never invented text.
- **Content rules (spec §15):** culture/entertainment tone; no fear-based wording, no death/illness predictions, no "giải hạn"/paid remedy offers; every reading ends with the `disclaimer` frame.
- **Voice:** Lão Thiên Cơ says "ta", calls the user "con"; Vietnamese only in F1.
- chart-svc changes are **additive** to `contracts/openapi/chart-svc.yaml` (no field removed/renamed); `school` strings unchanged.
- Stable ASCII keys: existing F1.1 keys unchanged; new star keys = PascalCase ASCII fold of the Vietnamese label (`Đ`→`D`, diacritics dropped, spaces removed), e.g. `Thiên Riêu`→`ThienRieu`.
- Knowledge key grammar (spec §6.4): `tuvi:` then `facet=Value` pairs joined by `:` in this fixed facet order `star, palace, brightness, tuhoa, major, mark` — plus the special families `tuvi:daily:area=<Area>:level=<Level>` and `tuvi:frame=<id>`. Regex: `^tuvi:(frame=[a-z_]+|daily:area=(CongViec|DiLai|TienBac|SucKhoe):level=(Thuan|Binh|LuuY)|[a-z]+=[A-Za-z]+(:[a-z]+=[A-Za-z]+)*)$`.
- Rust: all cargo via `scripts/cargo.sh`; Node only `docker run node:22-alpine`; Python only `docker run python:3.12-alpine`. Java via `mvn` (existing).
- Images non-root, multi-arch, `ghcr.io/<owner>/reading-tuvi`; compose port `127.0.0.1:8083`, `mem_limit` 384m (dev machine budget).

## Review Focus

1. **Doctrine record missing for a key** (the normal state until F1.2b) → that line uses the `missing` frame; the reading still returns 200 with every section present. Test in Task 6.
2. **Palace with no major star (vô chính diệu)** → key `tuvi:palace=<P>:major=none`, not a crash or empty section. Test in Task 4.
3. **chart-svc down / 5xx / timeout** → reading-tuvi returns 503 problem+json slug `upstream-unavailable` within 3 s, no stack trace, no birth data in logs; chart-svc 400 is passed through as reading-tuvi 400 with the same field names. Test in Task 6.
4. **Re-import of unchanged knowledge files** (every restart) → no new versions, cache stays valid; a changed record bumps `knowledge_version` and invalidates cached readings. Test in Task 5.
5. **Bad knowledge file line** (invalid key, empty content, wrong lang, duplicate key) → import fails fast naming file:line, service does not start with half-imported data (single transaction). Test in Task 5.

---

### Task 1: chart-svc — Tuần/Triệt, adjective stars, 12-god rings, tiểu hạn

**Files:**
- Modify: `services/chart-svc/src/domain/chart.rs` (new keys + labels + fields), `src/adapters/engine.rs` (mapping), `src/adapters/http.rs` (DTOs), `contracts/openapi/chart-svc.yaml`, `tools/fixtures/iztro/generate.mjs` + regenerated fixture, `tests/golden.rs`, snapshot
- Modify: `docs/superpowers/plans/2026-11-24-plan-f1-1-chart-svc-tuvi.md` Global Constraints — replace the old `school` strings and hour rule with a pointer to ruling R8 (`hour=local-standard`), one line.
- Create: `services/chart-svc/src/domain/void.rs` (Tuần/Triệt)

**Interfaces:**
- Produces (domain): `pub fn tuan(year: CanChi) -> [Branch; 2]`, `pub fn triet(year_stem: Stem) -> [Branch; 2]`; `pub enum AdjStarKey` (adjective stars), `pub enum RingStarKey` (members of the 4 rings), both with `key()`/`name_vi()` in the same table style as `StarKey`; `Palace` gains `adjective: Vec<AdjStarKey>`, `changsheng: RingStarKey`, `boshi: RingStarKey`, `suiqian: RingStarKey`, `jiangqian: RingStarKey`, `small_limit_ages: Vec<u8>`; `Chart` gains `tuan: [Branch; 2]`, `triet: [Branch; 2]`.
- Produces (HTTP, additive): `Palace.adjective: [{key,nameVi}]`, `Palace.rings: {changsheng, boshi, suiqian, jiangqian}` each `{key,nameVi}`, `Palace.smallLimitAges: [int]`, `Palace.tuan: bool`, `Palace.triet: bool`, `Chart.tuan: [Branch,Branch]`, `Chart.triet: [Branch,Branch]`.

- [ ] **Step 1: Failing domain tests** (`void.rs`):
  - `tuan(Giáp Tý) == [Xu, Hai]`; `tuan(Giáp Tuất) == [Shen, You]`; `tuan(Ất Sửu) == [Xu, Hai]`; `tuan(Quý Hợi) == [Zi, Chou]`; `tuan(Bính Dần) == [Xu, Hai]`; `tuan(Canh Ngọ) == [Xu, Hai]`; `tuan(Giáp Thân) == [Wu, Wei]`. Formula: with stem index s and branch index b, empty = `(b−s+10) mod 12`, `(b−s+11) mod 12`.
  - `triet`: Giáp/Kỷ → `[Shen, You]`; Ất/Canh → `[Wu, Wei]`; Bính/Tân → `[Chen, Si]`; Đinh/Nhâm → `[Yin, Mao]`; Mậu/Quý → `[Zi, Chou]` (all 10 stems asserted).
- [ ] **Step 2: Failing golden test** — extend `generate.mjs` (iztro@2.6.3, unchanged case list) to also emit per palace `adjectiveStars[].name` keys, `changsheng12`, `boshi12`, `suiqian12`, `jiangqian12`, `ages`; `golden.rs` compares all of them for all 333 cases. Tuần/Triệt-type adjective stars (`xunkong, xunzhong, jiekong, jielu, kongwang`) are **excluded** from `adjective` (chart-svc reports Tuần/Triệt as palace pairs instead); the golden test filters them out on both sides.
- [ ] **Step 3: Run** `scripts/cargo.sh test -p chart-svc` → FAIL.
- [ ] **Step 4: Implement.** Labels = x-iztro `vi_vn` labels with these Vietnamese-convention overrides (label and therefore key): `Thiên Diêu→Thiên Riêu`, `Trường Sinh→Tràng Sinh`, `Bác Sỹ→Bác Sĩ`, `Lực Sỹ→Lực Sĩ`, `Phụng Các→Phượng Các`, `Đài Phụ→Thai Phụ`. Rings live in their own enum so `Phi Liêm` / `Kiếp Sát` duplicates across lists never collide (ring keys are prefixed by nothing but stay in `RingStarKey`). An engine star with no mapping is an error (as F1.1), covered by the golden run. Regenerate the HTTP snapshot.
- [ ] **Step 5: Tests PASS**; fmt, clippy `-D warnings`, deny clean; contract test still validates responses against the YAML.
- [ ] **Step 6: Commit** `feat(chart): Tuần/Triệt, adjective stars, rings and tiểu hạn ages`.

---

### Task 2: Vietnamese cross-check against `lasotuvi` (~50 charts)

**Files:**
- Create: `tools/fixtures/lasotuvi/{README.md, generate.py, cases.json}`, `services/chart-svc/tests/fixtures/lasotuvi.json`, `services/chart-svc/tests/vn_crosscheck.rs`, `docs/tuvi/vn-school-diff.md`

**Interfaces:**
- Consumes: `POST /tuvi/chart` semantics via `ChartService` in-process (as `golden.rs`).

- [ ] **Step 1:** `generate.py` runs in `docker run --rm python:3.12-alpine` and installs `lasotuvi` from `https://github.com/doanguyen/lasotuvi` **pinned to a commit SHA** recorded in README (MIT; record licence). 50 cases in `cases.json`: 1 per year for 30 years spread 1930–2025, both genders, all 12 hour branches represented, 6 dates in VN-only leap months or VN/CN-differing Tết windows (reuse F1.1 Review Focus dates 1985-01-21, 1968-01-29, 2007-02-17, 2023-04-01), birthplace `Asia/Ho_Chi_Minh`, calendar VN. Output per case: lunar date, year can chi, cục, Mệnh branch, Thân branch, branch of each of 14 major stars, Tuần pair, Triệt pair, and lasotuvi's adjective stars per branch where its names map to our keys.
- [ ] **Step 2: Failing test** `vn_crosscheck.rs`: for every case, **must-match** facts — lunar date, year can chi, cục, Mệnh/Thân branch, 14 major star branches, Tuần pair, Triệt pair — equal chart-svc's; minor/adjective differences are not asserted but listed by a `--ignored` report test that prints a diff table.
- [ ] **Step 3: Run** → FAIL until fixture exists, then evaluate. A must-match difference is either (a) an input-convention difference (e.g. lasotuvi's hour or giờ Tý rule) — normalise the input in `generate.py` and say so in README — or (b) a real disagreement: **stop and report it** (BLOCKED) with the case; do not edit chart-svc to match without a controller ruling.
- [ ] **Step 4:** Write `docs/tuvi/vn-school-diff.md`: table of adjective/minor star placements where lasotuvi (Vietnamese school) differs from iztro default, counts per star, one line each on which convention the difference reflects if lasotuvi's source states it. This is the F1.x backlog of Vietnamese-school differences (spec §11); no engine change in this plan.
- [ ] **Step 5: Tests PASS**; commit `test(chart): Vietnamese cross-check against lasotuvi`.

Note: spec §11 asks for 2 Vietnamese tools; only `lasotuvi` is open-source with a licence. The second tool is out of scope here (ledger ruling expected).

---

### Task 3: reading-tuvi skeleton

**Files:**
- Create: `services/reading-tuvi/{pom.xml, Dockerfile, src/main/java/dev/platform/reading/tuvi/ReadingTuviApplication.java, src/main/resources/{application.yml, db/migration/V1__knowledge.sql}, src/test/java/dev/platform/reading/tuvi/{ArchitectureTest.java, HealthIT.java}}`
- Modify: root `pom.xml` (`<module>services/reading-tuvi</module>`), `deploy/local/init-db.sql` (`CREATE USER reading …; CREATE DATABASE reading_tuvi OWNER reading;`)

**Interfaces:**
- Produces: Flyway V1 tables
  - `knowledge(id bigserial pk, key text not null, school text not null, lang text not null, topic text, content text not null, source_ref text not null, status text not null check (status in ('draft','approved','hidden')), version int not null, content_sha256 text not null, updated_at timestamptz not null default now(), unique(key, school, lang))`
  - `knowledge_meta(id int pk check (id = 1), version bigint not null)` seeded with `(1, 0)`
  - `basic_reading_cache(chart_hash text, knowledge_version bigint, body jsonb not null, created_at timestamptz default now(), primary key (chart_hash, knowledge_version))`

- [ ] **Step 1: Failing tests:** `ArchitectureTest` — copy order-svc's rules for package `dev.platform.reading.tuvi` (domain free of Spring/Jackson/JDBC; adapters don't depend on each other's internals). `HealthIT` (Testcontainers Postgres) — `/health/live` and `/health/ready` 200; unknown route → 404 problem+json.
- [ ] **Step 2: Run** `mvn -q -pl services/reading-tuvi -am verify` → FAIL.
- [ ] **Step 3: Implement** by copying order-svc's pom (drop Kafka/outbox deps), application.yml (env names from Global Constraints) and Dockerfile.
- [ ] **Step 4: PASS**, Spotless applied; commit `feat(reading): reading-tuvi skeleton`.

---

### Task 4: Domain — chart model, key derivation, "Hôm nay" scoring

**Files:**
- Create under `src/main/java/dev/platform/reading/tuvi/domain/`: `Chart.java` (records `Chart`, `Palace`, `StarPlacement`, `DailyFacts`, `FlowLayer` mirroring chart-svc JSON keys as enums/strings), `KnowledgeKey.java`, `ReadingPlan.java`, `TodayScorer.java`, `Area.java`, `Level.java`
- Test: `src/test/java/dev/platform/reading/tuvi/domain/{KnowledgeKeyTest, ReadingPlanTest, TodayScorerTest}.java`

**Interfaces:**
- Produces:
  - `record KnowledgeKey(String value)` — validates the Global Constraints regex; factories `star(star, palace, brightness)`, `star(star, palace)`, `star(star)`, `palaceTuhoa(palace, mutagen)`, `palaceNoMajor(palace)`, `palaceMark(palace, "Tuan"|"Triet")`, `palace(palace)`, `daily(Area, Level)`, `frame(String id)`.
  - `ReadingPlan.basic(Chart) -> List<Section>`; `record Section(String id, String titleFrame, List<Line> lines)`; `record Line(List<KnowledgeKey> lookupOrder, Map<String,String> vars)` — first key found wins, none found → `frame=missing`.
  - `enum Area { CongViec(QuanLoc), DiLai(ThienDi), TienBac(TaiBach), SucKhoe(TatAch) }`, `enum Level { Thuan, Binh, LuuY }`
  - `TodayScorer.score(Chart natal, DailyFacts day) -> Map<Area, Level>` (ordered as the enum)

- [ ] **Step 1: Failing tests.**
  - `KnowledgeKeyTest`: `star("TuVi","Menh","Mieu").value() == "tuvi:star=TuVi:palace=Menh:brightness=Mieu"`; `palaceTuhoa("PhuThe","Ky") == "tuvi:palace=PhuThe:tuhoa=Ky"`; `palaceNoMajor("TaiBach") == "tuvi:palace=TaiBach:major=none"`; `daily(CongViec, Thuan) == "tuvi:daily:area=CongViec:level=Thuan"`; `frame("greeting") == "tuvi:frame=greeting"`; constructor rejects `"tuvi:Star=TuVi"`, `"astro:planet=Sun"`, `""`.
  - `ReadingPlanTest` on a hand-built chart fixture (`src/test/resources/chart-fixture.json`, a real chart-svc response captured in Task 6 step 1 — until then a hand-written one with the same shape): sections in this exact order — `greeting`, `menh`, `than`, `cuc`, then the 11 other palaces in chart order starting after Mệnh (`PhuMau, PhucDuc, DienTrach, QuanLoc, NoBoc, ThienDi, TatAch, TaiBach, TuTuc, PhuThe, HuynhDe`), then `closing`, `disclaimer`. For a palace section, lines: palace intro (`palace(P)`), one line per major star with lookup order `[star(s,P,b), star(s,P), star(s)]`, or one `palaceNoMajor(P)` line when there is none; one line per natal mutagen in that palace `palaceTuhoa(P,m)`; one line each for Tuần / Triệt when the palace branch is in the chart's pair (`palaceMark(P,"Tuan")`). The `than` section has the frame `than_intro` with var `palace` = Thân palace label and no star lines (stars are read in its own palace section).
  - `TodayScorerTest` — rule (Claude's call, spec §14.1, documented in the class Javadoc): for each area palace, add **daily** mutagens whose star sits in that natal palace (Lộc +2, Quyền +1, Khoa +1, Kỵ −2) and **monthly** mutagens (Lộc +1, Quyền 0, Khoa 0, Kỵ −1); +1 if the daily layer's `palaceKey` is the area palace; −1 if the area palace is in Triệt, −1 if in Tuần. Level: score ≥ 2 → `Thuan`, ≤ −2 → `LuuY`, else `Binh`. Cases: daily Lộc in TaiBach → `TienBac=Thuan`; daily Kỵ in QuanLoc → `CongViec=LuuY`; daily Khoa in ThienDi + ThienDi in Tuần → `DiLai=Binh`; nothing touching TatAch → `SucKhoe=Binh`; daily layer on TaiBach + monthly Lộc in TaiBach → `TienBac=Thuan`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (pure Java, no framework). **Step 4: PASS**, coverage ≥ 80% domain. **Step 5: Commit** `feat(reading): reading keys, basic plan and Hôm nay scoring`.

---

### Task 5: Knowledge files, importer and seed (voice frames + daily lines)

**Files:**
- Create: `contracts/knowledge/tuvi-record.schema.json`, `knowledge/tuvi/README.md`, `knowledge/tuvi/frames.vi.jsonl`, `knowledge/tuvi/daily.vi.jsonl`
- Create: `application/KnowledgePort.java`, `adapter/out/knowledge/{JdbcKnowledgeRepository.java, JsonlKnowledgeImporter.java}`
- Test: `adapter/out/knowledge/JsonlKnowledgeImporterIT.java`, `KnowledgeFilesTest.java` (validates every repo JSONL against the schema and key regex)

**Interfaces:**
- Record (one JSON object per line): `{"key", "school": "iztro-default", "lang": "vi", "topic"?: one of tinh-cach|tinh-duyen|su-nghiep|tai-loc|suc-khoe|van-han, "content", "source_ref", "status": "draft"|"approved"|"hidden"}`. `school` is the engine school family without calendar suffix (`iztro-default`). Frames/daily lines use `source_ref` `"original:frame"` / `"original:daily"`.
- `KnowledgePort.find(KnowledgeKey key, String lang) -> Optional<String>` (approved only), `KnowledgePort.version() -> long`.
- `JsonlKnowledgeImporter.importDir(Path dir) -> ImportResult(int inserted, int updated, int unchanged)` — one transaction; upsert by `(key, school, lang)`; `content_sha256` unchanged → untouched; changed → `version+1`; any insert/update → `knowledge_meta.version+1` once per import.

- [ ] **Step 1: Write seed content** (Vietnamese, Lão Thiên Cơ voice — "ta"/"con", warm, unhurried, no fear, no remedies, ≤ 2 sentences each; placeholders `{palace}`, `{star}`, `{cuc}`, `{date}`, `{lunar}`, `{canchi}` only):
  - `frames.vi.jsonl`, exactly these ids: `greeting, menh_title, than_title, than_intro, cuc_title, cuc_intro, palace_title, missing, closing, disclaimer, today_opening, today_closing`. `missing` must read as the old man not yet having the book open for this point (e.g. "Điều này ta chưa lật tới trang sách, con hỏi lại ta sau nhé."), not as an error. `disclaimer` states the reading is for cultural reference/entertainment.
  - `daily.vi.jsonl`: 12 records `tuvi:daily:area=<A>:level=<L>` for 4 areas × 3 levels, status `approved`.
- [ ] **Step 2: Failing tests.** `KnowledgeFilesTest`: every line in `knowledge/tuvi/*.jsonl` passes the schema + key regex; no duplicate `(key, school, lang)`; every frame id above present exactly once. `JsonlKnowledgeImporterIT` (Testcontainers): first import of the seed → `inserted == 24`, meta version 1; second identical import → `unchanged == 24`, meta version still 1; edit one content → `updated == 1`, that record `version == 2`, meta version 2; a file whose line 3 has key `tuvi:Star=X` → exception message contains `<file>:3` and table row count unchanged; empty `content`, `lang` ≠ `vi`, duplicate key in one file → rejected the same way.
- [ ] **Step 3: Implement**; importer runs from an `ApplicationRunner` when `KNOWLEDGE_DIR` is set, before readiness turns UP. **Step 4: PASS.** **Step 5: Commit** `feat(reading): knowledge schema, importer and Lão Thiên Cơ seed`.

---

### Task 6: Use cases + HTTP API (`/tuvi/readings/basic`, `/tuvi/readings/today`)

**Files:**
- Create: `application/{ChartPort, ReadingCachePort, BasicReadingUseCase, TodayUseCase, ReadingService}.java`, `adapter/out/chart/ChartSvcClient.java`, `adapter/out/persistence/JdbcReadingCache.java`, `adapter/in/web/{ReadingController, BirthRequest, TodayRequest, BasicReadingResponse, TodayResponse}.java`, `contracts/openapi/reading-tuvi.yaml`
- Test: `application/ReadingServiceTest.java`, `adapter/out/chart/ChartSvcClientTest.java` (MockRestServiceServer), `adapter/in/web/ReadingControllerTest.java`, `ContractTest.java`, `ReadingFlowIT.java`, `src/test/resources/chart-fixture.json`, `daily-fixture.json`

**Interfaces:**
- `ChartPort.chart(Birth) -> ChartResult(Chart chart, String chartHash)`, `ChartPort.daily(Birth, LocalDate) -> DailyFacts`; `chartHash` = lowercase hex SHA-256 of chart-svc's response body bytes (chart-svc output is byte-deterministic).
- `POST /tuvi/readings/basic` body = chart-svc `ChartRequest` fields (`birth, zone, gender, calendar`) → `200 {character:"LaoThienCo", school, chartHash, knowledgeVersion, sections:[{id, title, lines:[string]}]}` — the last section is `disclaimer`.
- `POST /tuvi/readings/today` body = same + `date` (YYYY-MM-DD) → `200 {date, lunar, dayCanChi, character:"LaoThienCo", opening, areas:[{area, label, level, line}] (4, enum order), closing}`; `label` Vietnamese: `Công việc, Đi lại, Tiền bạc, Sức khỏe`.
- Errors: chart-svc 400 → 400 `validation-error` with chart-svc's `errors` copied; chart-svc 5xx / connect error / > 3 s → 503 slug `upstream-unavailable`, detail `"Chart service unavailable"`.

- [ ] **Step 1:** Start chart-svc locally (`docker compose … --profile app up -d chart-svc`), capture one chart response and one daily response into the two fixtures (birth `1994-03-12T08:30`, `Asia/Ho_Chi_Minh`, `male`, `VN`, date `2026-11-24`); re-point Task 4's `ReadingPlanTest` to the real fixture if it was hand-written.
- [ ] **Step 2: Failing tests.**
  - `ReadingServiceTest` (fake ports): basic reading with an empty knowledge store → all sections present, every star line equals the rendered `missing` frame, 17 sections, `disclaimer` last; with a record for `star(TuVi,Menh,Mieu)` present → that text used; with only `star(TuVi)` present → fallback used. Second call with same chartHash and knowledge version → cache hit (ChartPort called, KnowledgePort not). Today: areas in enum order with lines from the daily records; placeholders rendered (`{palace}` → `Mệnh`, never a raw `{`).
  - `ChartSvcClientTest`: 400 pass-through with field names; 500 → `UpstreamUnavailable`; delay 4 s → `UpstreamUnavailable` (read timeout 3 s, connect timeout 1 s).
  - `ReadingControllerTest`: malformed JSON → 400 field `body`; missing `date` on today → 400 field `date`; 503 shape; log capture contains neither `1994-03-12` nor `08:30` on any path including errors.
  - `ContractTest`: both responses and the 400/503 problems validate against `reading-tuvi.yaml` (copy order-svc's ContractSupport).
  - `ReadingFlowIT` (Testcontainers Postgres + MockRestServiceServer or WireMock-free stub bean for ChartPort): import seed → POST basic → 200, `knowledgeVersion == 1`, `sections.size() == 17`; POST today → 4 areas.
- [ ] **Step 3: Implement.** **Step 4: PASS**, `mvn -q verify` green (coverage gate). **Step 5: Commit** `feat(reading): basic reading and Hôm nay HTTP API`.

---

### Task 7: Image, local stack, CI, smoke

**Files:**
- Modify: `deploy/local/docker-compose.yml` (service `reading-tuvi`, profile `app`, port `127.0.0.1:8083:8083`, `mem_limit: 384m`, env from Global Constraints with `CHART_SVC_URL=http://chart-svc:8082`, `KNOWLEDGE_DIR=/app/knowledge/tuvi`, depends on postgres healthy + chart-svc), `.github/workflows/ci.yml` (path filter `services/reading-tuvi/**`, `knowledge/tuvi/**`, `contracts/openapi/reading-tuvi.yaml` → `_java-service.yml` with `module: services/reading-tuvi`, `image: reading-tuvi`; `ci-gate` needs it), `CLAUDE.md` (repo map + commands)
- Modify: `services/reading-tuvi/Dockerfile` copies `knowledge/tuvi` to `/app/knowledge/tuvi` (build context = repo root)
- Create: `scripts/smoke-reading.sh`

- [ ] **Step 1:** `scripts/smoke-reading.sh <reading-url>` (`set -euo pipefail`): POST basic for the Task 6 fixture birth → 200, 17 sections, last section `disclaimer`; POST today → 4 areas each with a non-empty line; prints `OK sections=17 areas=4`.
- [ ] **Step 2:** `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build` (if an old `pgdata` volume exists, `down -v` once so `init-db.sql` creates `reading_tuvi`); run both smokes (`smoke-chart.sh`, `smoke-reading.sh`) → OK; `docker stats --no-stream` shows reading-tuvi under 384 MiB; stack down.
- [ ] **Step 3:** Push branch, open PR, `ci-gate` green (any CI failure → lesson via `retro`). Do NOT merge — controller merges after final review.
- [ ] **Step 4: Commit** `ci(reading): image, local stack and smoke`.
