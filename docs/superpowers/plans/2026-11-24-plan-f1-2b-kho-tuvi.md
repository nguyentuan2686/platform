# Plan F1.2b — Kho luận giải tử vi (tiếng Việt) do Claude biên soạn từ kinh sách phạm vi công cộng

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill `knowledge/tuvi/` with approved Vietnamese doctrine records for every key the basic reading looks up (tier P1) plus star×palace×brightness for the four most-read palaces (tier P2), compiled by Claude at build time from the public-domain 紫微斗數全書, each traceable to a source passage, machine-checked, and previewed as full rendered readings for Tuấn.

**Architecture:** No new service. Sources are fetched once from zh.wikisource and committed under `knowledge/sources/tuvi/` with their licence basis. A Java `KeyCatalog` in reading-tuvi's domain enumerates the key universe from the same factories `ReadingPlan` uses, so coverage is measured against what the reading actually asks for. Compilation is done by implementer subagents writing JSONL batches by hand (no LLM API, no runtime model). A `KnowledgeQualityTest` enforces the content rules on every record. A preview script renders three full readings from the local stack into a Markdown file for human reading.

**Tech Stack:** existing reading-tuvi (Java 21), JSONL + JSON Schema (`contracts/knowledge/tuvi-record.schema.json`), `curl` against the MediaWiki API (fetch only), Docker compose local stack for the preview.

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §6.4 (knowledge record, pipeline steps 1–4), §11 (PD-only source rule, `license_basis`), §14.2 (Lão Thiên Cơ voice), §15 (content/legal rules). **Spec amendment (Tuấn, 2026-11-24, "Claude soạn"):** §6.4 pipeline steps 2–3 ("LLM trích xuất / dịch biên soạn") are performed by Claude at build time instead of a free-tier LLM batch; the runtime still never calls Claude. Task 1 writes this amendment into the spec. Builds on Plan F1.2a (merged, main d5015d8).

## Global Constraints

- **Sources (hard rule, spec §11):** only public-domain originals. F1.2b uses 紫微斗數全書 (Ming dynasty, attributed to 陳希夷, ed. 羅洪先) as transcribed on zh.wikisource (`紫微斗數全書/卷一`, `卷二`, `卷三`) — `license_basis: "PD-old: Ming-dynasty text (author died > 400 years ago); Wikisource transcription of a PD work"`. No modern books, translations, commentaries, websites or the x-iztro/iztro-docs knowledge pack.
- **Content is a new compilation, not a translation:** modern, natural Vietnamese; never a sentence-by-sentence rendering of the classical text; no Chinese characters in `content`.
- **Voice:** every doctrine line is spoken by Lão Thiên Cơ ("ta", "con"), 1–3 sentences, ≤ 320 characters, because the reading shows each record as one of his lines.
- **Content rules (spec §15) — enforced by test:** culture/entertainment tone; no predictions of death, accidents, serious illness, disaster, divorce or bankruptcy as certainties; no "giải hạn", cúng, bùa, lễ, or any paid remedy; no fear-based upsell; health only as gentle lifestyle notes, never diagnosis. Hard-banned substrings (case-insensitive): `chết`, `tử vong`, `tai nạn`, `ung thư`, `bệnh nặng`, `giải hạn`, `cúng`, `bùa`, `yểm`, `phá sản`, `ly hôn`, `tuyệt tự`, `đoản mệnh`, `yểu`. Unfavourable placements are phrased as tendencies with a constructive turn ("con nên…").
- **Traceability:** `source_ref` is `ziwei-quanshu:<juan>:<section heading as in the source>` when the passage speaks to the key, or `derived:ziwei-quanshu:<juan>:<heading>[,<juan>:<heading>…]` when the record combines the star's general nature with the palace's meaning (allowed; the classic does not cover every combination). Never empty, never a modern work.
- **Record format:** as F1.2a (`contracts/knowledge/tuvi-record.schema.json`): `school: "iztro-default"`, `lang: "vi"`, `status: "approved"` only after `KnowledgeQualityTest` passes; `topic` from `tinh-cach|tinh-duyen|su-nghiep|tai-loc|suc-khoe|van-han` chosen by the palace (Menh/PhucDuc/ThienDi→tinh-cach, PhuThe→tinh-duyen, QuanLoc/NoBoc→su-nghiep, TaiBach/DienTrach→tai-loc, TatAch→suc-khoe, PhuMau/HuynhDe/TuTuc→van-han) or by the star for `tuvi:star=<S>` (tinh-cach).
- **Never delete a record line**; unpublish with `status: "hidden"` (F1.2a README rule).
- Keys come only from `KeyCatalog`; a record whose key is not in the catalog fails the build.
- File layout: `knowledge/tuvi/doctrine/<family>.vi.jsonl`, families `star`, `palace`, `palace-nomajor`, `palace-mark`, `palace-tuhoa`, `star-palace`, `star-palace-brightness-<Palace>`.
- Each compile batch ≤ 60 records per commit so a reviewer can read every line.

## Review Focus

1. **Doctrine that contradicts itself across levels** (e.g. `star=ThatSat:palace=Menh` says "bình ổn" while `…:brightness=Mieu` says "nóng vội, đơn độc" as the main trait) — readers see the brightness line *instead of* the star×palace line, so each brightness record must stand alone and agree in substance with its star×palace parent. Checked by reviewer per batch; `KnowledgeQualityTest` asserts every P2 key has its P1 parent.
2. **Banned or fear wording slipping in via synonyms** ("mất mạng", "đổ vỡ hôn nhân", "nghèo khổ suốt đời") — the banned list catches literals only; reviewers read every line; the test also rejects absolute fate phrases `suốt đời`, `chắc chắn sẽ`, `không tránh khỏi`.
3. **Classical-Chinese leakage or translationese** (Hán-Việt strings copied from the source, e.g. "Tử Vi cư Ngọ, vô sát tấu, quan chí công khanh") — test rejects any CJK character; reviewers flag lines that read as word-for-word Hán Việt.
4. **The rendered reading reads as a list of disconnected fortune-cookie lines** — the preview (Task 6) is read end to end for three charts; repeated openings ("Con có…", "Sao này…") more than 3 times per section are flagged by the preview script.
5. **A catalog key with no record after P1** → the reading falls back to `missing`; P1 coverage must be 100% (test), P2 coverage is reported, not gated.

---

### Task 1: Spec amendment, sources and key catalog

**Files:**
- Modify: `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §6.4 (steps 2–3) and §12.3 — one paragraph recording the "Claude soạn lúc build" decision (date, Tuấn's words, runtime unchanged).
- Create: `tools/knowledge/fetch-wikisource.sh`, `knowledge/sources/tuvi/SOURCES.md`, `knowledge/sources/tuvi/ziwei-quanshu/{juan1,juan2,juan3}.txt`
- Create: `services/reading-tuvi/src/main/java/dev/platform/reading/tuvi/domain/KeyCatalog.java`, test `domain/KeyCatalogTest.java`
- Create: `services/reading-tuvi/src/test/java/dev/platform/reading/tuvi/KnowledgeCoverageTest.java`

**Interfaces:**
- `KeyCatalog.p1() -> List<KnowledgeKey>` = `star(S)` ×14, `palace(P)` ×12, `palaceNoMajor(P)` ×12, `palaceMark(P, Tuan|Triet)` ×24, `palaceTuhoa(P, M)` ×48, `star(S, P)` ×168 → **278** keys; `KeyCatalog.p2(String palace) -> List<KnowledgeKey>` = `star(S, palace, B)` for 14 stars × 5 levels = 70 per palace; `KeyCatalog.P2_PALACES = [Menh, QuanLoc, TaiBach, PhuThe]`. Stars/palaces/levels/mutagens taken from the domain enums (no new lists).
- `KnowledgeCoverageTest`: loads all `knowledge/tuvi/**/*.jsonl`, prints `P1 covered x/278, P2 <palace> y/70` and **fails if any record key is outside `p1() ∪ p2(*) ∪ frames ∪ daily`**. The 100% P1 gate is switched on in Task 4 (constant `P1_GATE = false` until then).

- [ ] **Step 1:** `fetch-wikisource.sh` downloads the wikitext of the three pages via `https://zh.wikisource.org/w/api.php?action=parse&page=<title>&prop=wikitext&format=json&formatversion=2`, strips markup to plain text (keep section headings as `== … ==` lines), writes UTF-8 files; idempotent; prints the page revision ids, which go into `SOURCES.md` with title, URL, revision id, fetch date, `license_basis` (Global Constraints wording).
- [ ] **Step 2: Failing tests** — `KeyCatalogTest`: `p1().size() == 278`; no duplicates; contains `tuvi:star=TuVi`, `tuvi:palace=Menh`, `tuvi:palace=QuanLoc:major=none`, `tuvi:palace=PhuThe:mark=Triet`, `tuvi:palace=TaiBach:tuhoa=Ky`, `tuvi:star=ThatSat:palace=Menh`; `p2("Menh").size() == 70` and contains `tuvi:star=TuVi:palace=Menh:brightness=Mieu`; every `ReadingPlan.basic(fixtureChart)` lookup key is in `p1() ∪ p2(any palace) ∪ star×palace×brightness for all palaces` (catalog cannot drift from the plan). `KnowledgeCoverageTest` passes with the current seed (no doctrine yet) and reports `P1 covered 0/278`.
- [ ] **Step 3:** Implement; `mvn -q -pl services/reading-tuvi verify` green.
- [ ] **Step 4: Commit** `docs+feat(knowledge): spec amendment, PD sources, key catalog`.

---

### Task 2: Content quality gate

**Files:**
- Create: `services/reading-tuvi/src/test/java/dev/platform/reading/tuvi/KnowledgeQualityTest.java`, `knowledge/tuvi/STYLE.md`

**Interfaces:**
- `KnowledgeQualityTest` over every doctrine record (not frames/daily): schema-valid; `school == "iztro-default"`, `lang == "vi"`; 1–3 sentences (split on `.`, `!`, `?`, `…` followed by space/end) and ≤ 320 chars; contains at least one Vietnamese diacritic letter; no CJK code point (U+3400–U+9FFF, U+F900–U+FAFF); no banned substring (Global Constraints list + `suốt đời`, `chắc chắn sẽ`, `không tránh khỏi`); contains `con` as a word (voice); `source_ref` matches `^(derived:)?ziwei-quanshu:juan[123]:.+` (multiple refs comma-separated after `derived:`); no two records share identical `content`; every P2 key's P1 parent `star(S,P)` exists; `topic` matches the palace/star mapping in Global Constraints.
- `STYLE.md`: the voice and content rules above in Vietnamese with 6 good / 6 bad example lines (bad ones show each banned pattern and translationese), and the `source_ref` convention. Written for the compiler subagents of Tasks 3–5.

- [ ] **Step 1: Failing tests** — a fixture directory `src/test/resources/knowledge-bad/` with one record per rule violation; the test's rule engine (a package-private `QualityRules.check(record) -> List<String>`) returns the expected violation for each; a clean fixture returns none. Then the real-files test iterates `knowledge/tuvi/doctrine/` (empty now → passes).
- [ ] **Step 2:** Implement; verify green. **Step 3: Commit** `test(knowledge): doctrine quality gate and style guide`.

---

### Task 3: Compile P1 — star, palace, no-major, Tuần/Triệt, palace×Tứ hóa (110 records)

**Files:** Create `knowledge/tuvi/doctrine/{star,palace,palace-nomajor,palace-mark,palace-tuhoa}.vi.jsonl`

- [ ] **Step 1:** Read `knowledge/tuvi/STYLE.md`, `knowledge/sources/tuvi/SOURCES.md` and the relevant source sections (卷一/卷二 star natures — 諸星問答論, 太微賦, 骨髓賦; palace meanings — the 十二宮 sections; 四化 — 論四化 / 化忌 passages; 旬空/截空 passages). Note for each record which heading it draws on.
- [ ] **Step 2:** Write the records in batches ≤ 60 (two commits): `star` 14 (general nature of each major star), `palace` 12 (what the palace governs), `palace-nomajor` 12 (an empty palace borrows the opposite palace's stars — say so, gently), `palace-mark` 24 (Tuần/Triệt in each palace: "slows / blocks early, eases later" family of meanings), `palace-tuhoa` 48 (Lộc/Quyền/Khoa/Kỵ natal in each palace; Kỵ phrased as a point to mind, never a curse).
- [ ] **Step 3:** `mvn -q -pl services/reading-tuvi verify` — `KnowledgeQualityTest` and `KnowledgeCoverageTest` green; report shows `P1 covered 110/278`.
- [ ] **Step 4: Commits** `content(knowledge): P1 star/palace/marks` and `content(knowledge): P1 palace×Tứ hóa`.

---

### Task 4: Compile P1 — star×palace (168 records) and switch on the P1 gate

**Files:** Create `knowledge/tuvi/doctrine/star-palace.vi.jsonl`; modify `KnowledgeCoverageTest` (`P1_GATE = true`).

- [ ] **Step 1:** For each of the 14 stars, read its passages (骨髓賦, 諸星問答論 per star, 紫微斗數全書 卷二/卷三 star-in-palace sections where present) and write 12 records (one per palace), in 3 commits of ≤ 60 (stars 1–5, 6–10, 11–14; star order as the domain enum).
- [ ] **Step 2:** Set `P1_GATE = true`: test fails if any P1 key lacks an approved record. Verify green, report `P1 covered 278/278`.
- [ ] **Step 3: Commits** `content(knowledge): star×palace <stars>` ×3, `test(knowledge): require full P1 coverage`.

---

### Task 5: Compile P2 — star×palace×brightness for Mệnh, Quan Lộc, Tài Bạch, Phu Thê (280 records)

**Files:** Create `knowledge/tuvi/doctrine/star-palace-brightness-{Menh,QuanLoc,TaiBach,PhuThe}.vi.jsonl`

- [ ] **Step 1:** For each palace, 14 stars × 5 levels. Each record refines its P1 parent `star(S,P)`: bright levels (Mieu/Vuong) emphasise the star's strengths, Dac/Binh balance, Ham names the tendency to mind with a constructive turn. Must agree in substance with the parent (Review Focus 1). Combinations the classic marks as impossible or never-occurring for the star still get a record (the engine decides levels; the corpus must not have holes).
- [ ] **Step 2:** 5 commits of ≤ 60 records (Menh 70 → 2 commits of 35, then one commit per remaining palace split at ≤ 60). Verify green after each; report P2 coverage `70/70` per palace.

---

### Task 6: Preview readings for Tuấn

**Files:** Create `scripts/preview-readings.sh`, `docs/tuvi/sample-readings.md`

- [ ] **Step 1:** `preview-readings.sh <reading-url>` posts three births — `1994-03-12T08:30 Asia/Ho_Chi_Minh male VN`, `1988-10-05T23:40 Asia/Ho_Chi_Minh female VN` (tests the 23:xx roll), `2001-07-20T14:15 Asia/Ho_Chi_Minh female VN` — to `/tuvi/readings/basic` and `/today` (date = run date), and writes Markdown: per chart, each section title then its lines as a dialogue (`**Lão Thiên Cơ:** …`), then "Hôm nay". It prints a warning line for any section where more than 3 lines start with the same first two words (Review Focus 4) and the count of `missing` lines (must be 0 for sections whose stars are P1-covered).
- [ ] **Step 2:** Bring up `postgres`, `chart-svc`, `reading-tuvi` (`--no-deps`), run the script, commit `docs/tuvi/sample-readings.md`, stack down (`down -v`).
- [ ] **Step 3:** Push branch, open PR, `ci-gate` green (CI failure → lesson per `retro`). Do NOT merge — controller merges after final review. Commit `docs(knowledge): sample readings preview`.
