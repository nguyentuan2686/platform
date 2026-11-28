# Xem số hợp mệnh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A free checker that scores a SIM, bank account number or licence plate against the user's mệnh and cung phi (0–10, six criteria), with a Lão Thiên Cơ reading assembled from pre-written knowledge, limited to 2 checks per person per VN day, storing no number.

**Architecture:** Pure domain scoring in `reading-tuvi` (`domain.number`), an application service that enforces the quota through a port backed by a new Postgres table holding only HMACs, a REST endpoint behind the internal token, and a Next.js route + four indexable pages in `web`. No LLM.

**Tech Stack:** Java 21 / Spring Boot / Flyway / JUnit 5 / Testcontainers / ArchUnit; Next.js app router / vitest / Playwright; Kustomize.

**Spec:** `docs/superpowers/specs/2026-11-28-xem-so-hop-menh-design.md` (approved 2026-11-28). Read it before any task: §5 (algorithm), §6 (knowledge keys), §7 (API, quota), §8 (privacy) carry the exact values.

## Global Constraints

- CLAUDE.md applies: hexagonal packages (`domain` has no Spring/Jackson/jakarta/java.sql), constructor injection, records for DTOs, RFC 9457 Problem Details with `trace_id`, coverage ≥ 80% for domain + application, Flyway for schema, `mvn spotless:apply` before commit.
- The number, its normalized form, birth year and gender never appear in logs, URLs, DB plaintext, analytics or error bodies.
- Quota: 2 distinct numbers per subject per VN day (`Asia/Ho_Chi_Minh`); 20 distinct numbers per IP hash per VN day; same `kind|normalized number` the same day is free. Settings `NUMBER_CHECK_DAILY_PER_SUBJECT=2`, `NUMBER_CHECK_DAILY_PER_IP=20`.
- `check_hash = HMAC-SHA256(NUMBER_CHECK_HMAC_KEY, kind|normalized)`; `ip_hash = HMAC-SHA256(key, ip)` with IPv6 reduced to its /64.
- All knowledge copy follows `knowledge/tuvi/STYLE.md` (ta/con, ≤ 320 chars, banned list, no fatalism, no advice to change or buy a number, no concrete numbers to bet on).
- Score weights/thresholds exactly as spec §5.3; total rounded to nearest 0.5, half up.
- Web: no native Node/Java on the dev machine is required by CI; local vitest may time out — CI is the verifier (lesson 2026-11-27).

## Review Focus

1. Input with spaces, dots, dashes, `+84` prefix, lowercase plate letters → normalized, not rejected. (Task 1 tests.)
2. Birth year at the edges (1930, current year, before-Tết in 2000 → 1999) and invalid years (1929, next year) → correct mệnh / 400 `invalid_birth`. (Task 1 tests.)
3. Third distinct number on the same day → 409 `number_quota` with quota body, while re-checking number 1 still returns 200. (Task 4 IT.)
4. Concurrent double-submit of two new numbers when one check is left → only one succeeds (advisory lock). (Task 4 IT.)
5. A failing upstream or 409 in web shows the friendly message and never logs the number. (Task 5 route test.)

---

### Task 1: Domain — input normalization, mệnh, cung phi

**Files:**
- Create in `services/reading-tuvi/src/main/java/dev/platform/reading/tuvi/domain/number/`: `NumberKind.java` (SIM, BANK, PLATE), `Vehicle.java` (CAR, MOTORBIKE), `NumberInput.java`, `InvalidNumberException.java`, `Element.java` (KIM, MOC, THUY, HOA, THO with `generates()`/`controls()`), `NapAm.java` (60-year table), `CungPhi.java`, `Profile.java`
- Test: `services/reading-tuvi/src/test/java/dev/platform/reading/tuvi/domain/number/NumberInputTest.java`, `NapAmTest.java`, `CungPhiTest.java`

**Interfaces — Produces:**
- `record NumberInput(NumberKind kind, String digits)`; `static NumberInput parse(NumberKind kind, Vehicle vehicle /*nullable unless PLATE*/, String raw)` throws `InvalidNumberException(String reason)` with reason `invalid_number`. `digits` is the sequence scored (spec §5.1). `String hashMaterial()` returns `kind.name().toLowerCase() + "|" + normalizedFull` (full normalized number, not just scored digits).
- `record Profile(int lunarYear, String canChi, String napAm, Element element, int cungPhi, String cungPhiName, boolean eastGroup)`; `static Profile of(int solarYear, boolean beforeTet, Gender gender, int currentYear)` throws `InvalidNumberException("invalid_birth")` when solarYear < 1930 or > currentYear. `enum Gender {MALE, FEMALE}` in the same package.

- [ ] **Step 1: Write failing tests**
  - `NumberInputTest`: `"+84 912 345 678"` SIM → digits `"0912345678"`; `"091234567"` → `invalid_number`; bank `"0123 4567 89"` → `"0123456789"`, 5 digits / 20 digits → invalid; plate CAR `"30a-123.45"` → `"12345"`; CAR `"29LD-1234"` → `"1234"`; MOTORBIKE `"29-B1 123.45"` → `"12345"`; MOTORBIKE `"59X3-1234"` → `"1234"`; PLATE with null vehicle → invalid; `hashMaterial()` of the two SIM spellings `"0912 345 678"` and `"+84912345678"` are equal.
  - `NapAmTest`: 1984 → Giáp Tý, Hải Trung Kim, KIM; 1990 → Canh Ngọ, Lộ Bàng Thổ, THO; 2027 → Đinh Mùi, Thiên Hà Thủy, THUY; all 60 years 1984–2043 produce 30 distinct nạp âm names each used exactly twice.
  - `CungPhiTest`: (1990, M) Khảm 1 east; (1990, F) Cấn 8 west; (2000, M) Ly 9; (2000, F) Càn 6; (2000 beforeTet, M) → lunar 1999 → Khảm; result 5 → male Khôn 2 (1986 M: r=8+6=14→5, 10−5=5 → Khôn), female Cấn 8 (2008 F: r=8, 6+8=14→5 → Cấn); 1929 → `invalid_birth`.
- [ ] **Step 2: Run** `mvn -q -pl services/reading-tuvi -am test -Dtest='NumberInputTest,NapAmTest,CungPhiTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (classes missing).
- [ ] **Step 3: Implement** per spec §5.1–5.2. Can = `(Y−4) mod 10`, chi = `(Y−4) mod 12`; nạp âm index = `((Y−4) mod 60) / 2`.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `feat(reading-tuvi): number input normalization, nạp âm and cung phi`.

### Task 2: Domain — scoring engine

**Files:**
- Create in `domain/number/`: `Trigram.java` (Tiên thiên 1..8 with east/west group per Bát trạch: Khảm, Chấn, Tốn, Ly east), `Hexagram.java` (64-entry table: upper, lower, Vietnamese name, `Fortune {CAT, BINH, HUNG}`), `FolkPairs.java` (good/avoid sequences), `Criterion.java`, `NumberScore.java`, `NumberScorer.java`, `Band.java`
- Test: `domain/number/NumberScorerTest.java`, `HexagramTest.java`

**Interfaces — Produces:**
- `record Criterion(String key /* element|hexagram|yinyang|cungphi|nut|pairs */, String label, String detail, double points, double max, String readingKey)`. `readingKey` is the knowledge key from spec §6 for this criterion's outcome (e.g. `so:element=sinh_nhap`, `so:que=11`, `so:pair=68` is NOT here — pairs give a list).
- `record NumberScore(double total, Band band, List<Criterion> criteria, Hexagram hexagram, Element numberElement, List<String> goodPairs, List<String> avoidPairs)`.
- `enum Band { RAT_HOP, KHA_HOP, BINH_THUONG, CHUA_HOP }` with `key()` → `rat_hop` etc. and Vietnamese `label()`.
- `static NumberScore NumberScorer.score(NumberInput in, Profile p)`.
- Hexagram id: King Wen number 1..64 (used in `so:que=<id>`).

- [ ] **Step 1: Write failing tests**
  - Digit element map exactly `0,1 THUY; 2,5,8 THO; 3,4 MOC; 6,7 KIM; 9 HOA`; tie → element of the last digit among tied elements.
  - Element points: number generates mệnh 3.0, same 2.5, mệnh controls number 1.5, mệnh generates number 1.0, number controls mệnh 0.
  - Hexagram: upper = sum(first half) mod 8, lower = sum(second half) mod 8, 0 → 8 for both; odd length first half shorter. `"12345"` → halves `"12"`,`"345"` → upper 3 Ly, lower 12 mod 8 = 4 Chấn → Hỏa Lôi Phệ Hạp (King Wen 21).
  - `HexagramTest`: 64 distinct (upper, lower) pairs, 64 distinct ids 1..64, Thái (Địa Thiên, upper Khôn 8, lower Càn 1) id 11 is CAT.
  - Yin-yang: imbalance `|even−odd|` ≤ ceil(0.2·len) → 1.5; ≤ ceil(0.4·len) → 0.75; else 0. 10 digits 5/5 → 1.5; 10 digits 8/2 → 0.
  - Cung phi: both trigrams in user's group 1.5, one 0.75, none 0.
  - Nút: sum mod 10; 9 → 0.5; 7, 8 → 0.25; other incl. 0 → 0.
  - Pairs: start 0.5, +0.25 per good occurrence, −0.25 per avoid occurrence, clamp [0, 1]. Good list at least: 68, 86, 39, 79, 38, 78, 66, 88, 99, 6789; avoid: 49, 53. Overlapping occurrences each count.
  - Total rounding: 9.25 → 9.5, 7.75 → 8.0, 6.2 → 6.0. Bands: ≥ 8 RAT_HOP; 6.5–7.5 KHA_HOP; 5–6 BINH_THUONG; < 5 CHUA_HOP.
  - Golden: SIM `0912345678`, (1990, M, Lộ Bàng Thổ, Khảm) → assert every criterion's points and total computed by hand in the test comment (write the derivation in the comment so a reviewer can re-check).
- [ ] **Step 2: Run** `-Dtest='NumberScorerTest,HexagramTest'` → FAIL.
- [ ] **Step 3: Implement.** Fortune classification of the 64 hexagrams follows the common Vietnamese SIM-site table (cát/bình/hung); keep it a single array literal with names.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(reading-tuvi): number scoring (six criteria, 64 hexagrams, folk pairs)`.

### Task 3: Knowledge content and reading assembly

**Files:**
- Create: `knowledge/tuvi/number.vi.jsonl` (all keys of spec §6, `status: approved`, `school: iztro-default`, `source_ref: original:number`)
- Modify: `domain/KeyCatalog.java` (register the `so:` keys: intro=sim|bank|plate, menh=5, element=5, que=1..64, amduong=3, cungphi=3, nut=cao|vua|thap, pair=<each FolkPairs entry>, band=4, closing)
- Create: `domain/number/NumberReadingPlan.java` — `static List<String> keys(NumberInput in, Profile p, NumberScore s)` in the order of spec §6 (pairs: at most 3, good first)
- Create: `application/number/NumberReading.java` — renders keys via existing `KnowledgePort` into `List<String>` paragraphs
- Test: `domain/number/NumberReadingPlanTest.java`; extend the existing knowledge quality test so every `so:` key in `KeyCatalog` has an approved record and passes `QualityRules`.

- [ ] **Step 1: Failing tests**: plan for the golden input starts `so:intro=sim`, `so:menh=tho`, ends `so:band=…`, `so:closing`; at most 3 `so:pair=` keys; nut mapping 9 → cao, 7–8 → vua, else thap. Quality test: missing record for any `so:` key fails.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement + write ~110 records.** Copy rules: Global Constraints. `so:closing` says it is a folk reference and that the person's own conduct matters most. Bank/plate intros must not suggest changing the number. No digit sequences of the form `\d{2}\s*[-–]\s*\d{2}`.
- [ ] **Step 4: Run** `mvn -q -pl services/reading-tuvi -am verify` → PASS (includes KnowledgeQualityTest, ArchUnit).
- [ ] **Step 5: Commit** `feat(reading-tuvi): number readings knowledge base and assembly`.

### Task 4: Application service, quota, persistence, endpoint, secrets

**Files:**
- Create: `application/number/NumberCheckService.java`, `NumberCheckCommand.java`, `NumberCheckResult.java`, `NumberQuota.java` (limit, used, remaining), `NumberQuotaExceededException.java`, `NumberUsagePort.java`, `NumberHasherPort.java`, `NumberCheckSettings.java`
- Create: `src/main/resources/db/migration/V6__number_check_usage.sql` (table per spec §7, PK `(subject_kind, subject_id, vn_day, check_hash)`, index on `(vn_day, ip_hash)`)
- Create: `adapter/out/persistence/JdbcNumberUsage.java`, `adapter/out/crypto/HmacNumberHasher.java`, `adapter/in/web/NumberCheckController.java` + `NumberCheckRequest.java` + `NumberCheckResponse.java`; cleanup method in `adapter/in/schedule/` (new `NumberJobs.java`, daily, deletes `vn_day < today − 1`)
- Modify: `adapter/in/web/ReadingExceptionHandler.java` (400 `invalid_number` / `invalid_birth`, 409 `number_quota` with `quota` property), `adapter/config/` (wire beans), `application.yml` (`number-check.hmac-key: ${NUMBER_CHECK_HMAC_KEY:}`, per-subject 2, per-ip 20; startup fails when the key is blank outside tests, like `QA_ENC_KEY`)
- Modify: `contracts/openapi/reading-tuvi.yaml` (both endpoints, schemas, reasons)
- Modify secrets: `deploy/k8s/overlays/prod/secrets.tsv` (add `NUMBER_CHECK_HMAC_KEY` to reading-tuvi-secrets), `deploy/k8s/overlays/e2e/secrets/reading-tuvi-secrets.env` (`local-test-…` value), `deploy/local/.env.example`, `deploy/local/docker-compose.yml` (pass the env), `deploy/k8s/overlays/prod/secrets.local/reading-tuvi-secrets.env` (gitignored; generate 32 random bytes base64) and reseal `reading-tuvi-secrets` with `scripts/seal-secrets.sh --only reading-tuvi-secrets` (see CLAUDE.md command).
- Test: `application/number/NumberCheckServiceTest.java` (fake ports), `adapter/out/persistence/JdbcNumberUsageIT.java`, `adapter/in/web/NumberCheckControllerIT.java`, extend `NoPiiInLogsIT`.

**Interfaces:**
- Consumes: Task 1–3 types; existing `IdentityPort.whoAmI(bearer)` → `IdentitySubject`.
- Produces HTTP: `POST /tuvi/number-check` body `{kind, vehicle?, number, birthYear, beforeTet, gender}` → 200 `{score, band, bandLabel, menh {canChi, napAm, element}, cungPhi {name, group}, criteria [{key,label,detail,points,max}], reading [string], quota {limit,used,remaining}}`; `GET /tuvi/number-check/quota` → `{limit, used, remaining}`. Headers: `X-Internal-Token`, `Authorization: Bearer`, `X-Client-Ip`.
- `NumberUsagePort.recordOrReuse(IdentitySubject s, LocalDate vnDay, byte[] checkHash, byte[] ipHash, int perSubject, int perIp)` → `NumberQuota` or throws `NumberQuotaExceededException(NumberQuota)`; one transaction with `pg_advisory_xact_lock` on the subject.

- [ ] **Step 1: Failing tests**
  - Service: invalid input → exception before any port call (no quota used); first and second distinct numbers OK (used 1, 2); third → `NumberQuotaExceededException` with remaining 0; first number again → OK, used stays 2; per-IP limit reached with a fresh subject → exceeded.
  - `JdbcNumberUsageIT` (Testcontainers): same as above against Postgres; a new VN day resets; two concurrent new numbers with 1 remaining → exactly one succeeds; cleanup deletes rows older than yesterday.
  - `NumberCheckControllerIT`: 200 body shape and that no response field equals the input number; 400 reasons; 409 `application/problem+json` with `reason: number_quota`, `quota`, `trace_id`; missing internal token → 401/403 as other endpoints.
  - `NoPiiInLogsIT`: run a number check with `0912345678` and birth year 1990; captured logs contain neither `0912345678`, `912345678` nor `1990`.
  - HMAC: IPv6 `2001:db8:1:2:aaaa::1` and `2001:db8:1:2:bbbb::2` hash equal; IPv4 unchanged.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** VN day from `Clock` + `Asia/Ho_Chi_Minh`. Never put the number in exception messages.
- [ ] **Step 4: Run** `mvn -q -pl services/reading-tuvi -am verify` and `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh` → both PASS.
- [ ] **Step 5: Commit** `feat(reading-tuvi): number-check endpoint with daily quota (HMAC only)`.

### Task 5: Web — route, pages, form, SEO, ingress

**Files:**
- Create: `services/web/src/lib/numberCheck.ts` (`checkNumber(input, bearer, clientIp)`, `numberQuota(bearer)` via `callJson("reading", …)`), `src/app/api/xem-so/route.ts` (POST) and `src/app/api/xem-so/quota/route.ts` (GET), `src/app/vi/xem-so/page.tsx`, `src/app/vi/xem-so/[loai]/page.tsx` (`loai` ∈ `sim | so-tai-khoan | bien-so-xe`, `generateStaticParams`, `dynamicParams = false`), `src/components/NumberCheckForm.tsx` (client), `src/content/xemSo.ts` (page titles, descriptions ≤ 160, intro paragraphs, method explanation; voice rules as `tuvi2027.ts`)
- Tests: `src/app/api/xem-so/route.test.ts`, `src/components/NumberCheckForm.test.tsx`, `src/content/xemSo.test.ts`, update `src/app/seo.test.ts` (44 → 48, sitemap regex adds `/xem-so(/(sim|so-tai-khoan|bien-so-xe))?`, metadata cases for the 4 pages), `src/app/sitemap.ts`, `e2e/seo.spec.ts` (48), new `e2e/xem-so.spec.ts` with stub upstream
- Modify: `src/app/vi/page.tsx` (link next to the Tử vi 2027 link), `src/components/SiteFooter.tsx` + test ("Xem số hợp mệnh"), e2e stub upstream to serve `/tuvi/number-check`, `deploy/k8s/overlays/prod/apps/ingress.yaml` (add `/api/xem-so` to `web-strict` paths), `docs/runbooks/web.md` (one line)

**Interfaces:** Consumes Task 4 HTTP contract.

- [ ] **Step 1: Failing tests**
  - Route: CSRF refusal → 403; invalid body (missing gender, plate without vehicle, non-integer year) → 400 without calling upstream; upstream 409 → 409 `{reason:"number_quota", quota}` and logger not called; upstream error → 503; response header `Cache-Control: no-store`; logger spy never receives a string containing the submitted number.
  - Form: renders the bank warning only on `so-tai-khoan`; vehicle select only on `bien-so-xe`; submitting posts JSON to `/api/xem-so` (not a GET, URL unchanged); shows score, band, 6 criteria rows, reading paragraphs; on 409 shows "Hôm nay con xem đủ 2 số rồi, mai quay lại với ta nhé." and a link to `/vi`.
  - Content: same STYLE checks as `tuvi2027.test.ts` (reuse its banned list), descriptions 41–160 chars.
  - e2e: fill SIM form → score visible; third distinct number → quota message; mobile 375 px no horizontal scroll.
- [ ] **Step 2: Run** web gates (CLAUDE.md docker command) → FAIL.
- [ ] **Step 3: Implement.** Pages are indexable (`pageMeta`), breadcrumbs via `breadcrumbLd`. Client IP from `lib/clientIp.ts`, session via `ensureSession()`, errors via `upstreamFailure(e, "xem-so")`.
- [ ] **Step 4: Run** web gates + `k8s-validate.sh` → PASS (local vitest timeouts: rely on CI).
- [ ] **Step 5: Commit** `feat(web): xem số hợp mệnh pages (SIM, số tài khoản, biển số xe)`.

### Task 6: Smoke and docs

**Files:** `scripts/smoke-reading.sh` (POST one SIM check: expect 200, `score` numeric, 6 criteria), `CLAUDE.md` (smoke-reading line mentions the number check), `docs/runbooks/go-live.md` (new secret key in the reading-tuvi-secrets list).

- [ ] **Step 1:** Add the smoke step; run `bash -n scripts/smoke-reading.sh` and the script's test under `scripts/test/` if one exists → PASS.
- [ ] **Step 2: Commit** `chore: smoke and docs for number check`.
