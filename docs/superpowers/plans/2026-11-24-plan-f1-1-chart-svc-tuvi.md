# Plan F1.1 — chart-svc: lá số tử vi + lịch âm Việt + dữ kiện xem ngày

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A stateless Rust service `chart-svc` turns a birth (local date-time, IANA zone, gender, calendar VN|CN) into a correct Tử Vi Đẩu Số chart and, for a given day, the flow facts (lưu niên / lưu nguyệt / lưu nhật) that F1.2's "Hôm nay" reading needs — proven by golden tests against the reference engine iztro and by pinned Vietnamese-calendar dates.

**Architecture:** New workspace member `services/chart-svc`, hexagonal like notify-svc but without DB/Kafka: `domain` (pure: Vietnamese/Chinese lunar calendar, birth-time normalisation, chart value types with stable keys + Vietnamese labels), `application` (`ZiweiEngine` port + `ChartService`), `adapters::{engine (x-iztro), http}`. Built on `platform-core` (telemetry, Problem Details, health, trace layer). The engine never sees Vietnamese-calendar logic: for `calendar=VN` the service computes the lunar date itself (UTC+7) and feeds the engine a lunar date; for `CN` it passes the solar date.

**Tech Stack:** Rust 1.100 workspace (existing), axum/tokio/serde via workspace deps, `x-iztro` (iztro v2.5.8 port; fallback `iztro` crate — see Task 4), `jiff` with bundled tz database for historical offsets, `time` (chrono is banned by cargo-deny), Node 22 in Docker only to generate golden fixtures from JS `iztro@2.5.8`.

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` (§6.3 chart-svc, §11 golden tests + Vietnamese school, §12.1 calendar override, §14.1 daily facts). Platform rules: `CLAUDE.md`, `.claude/skills/rust-service-conventions/SKILL.md`. Pattern to copy: `services/notify-svc` (Plan 2).

## Global Constraints

- Logs / HTTP errors / health exactly as platform (`CLAUDE.md` "Logging / observability contract"); 400s use slug `validation-error` with `errors: [{field, message}]`; 500 detail exactly `"Unexpected error"`.
- chart-svc is **stateless**: no DB, no Kafka. `/health/ready` is UP when the process can serve (no external checks).
- Pure function: same input → byte-identical JSON output (no clocks, no randomness in the chart path).
- Config via env: `PORT` (default `8082`), `OTEL_EXPORTER_OTLP_ENDPOINT` (unset → no exporter), `OTEL_SERVICE_NAME` (default `chart-svc`), `RUST_LOG` (default `info`).
- Supported birth years: 1900–2100 inclusive (solar). Outside → 400 on field `birth`.
- **Giờ Tý rule (spec §6.3):** 23:00–23:59 local standard time counts as the **next solar day**, hour branch Tý.
- **Calendar VN:** lunar calendar computed at UTC+7 (Hồ Ngọc Đức method: new moons + major solar terms, astronomical, not tables). **CN:** UTC+8. Birth time is first converted from the birth zone's historical offset to the calendar's standard offset (VN +7, CN +8).
- Stable keys are ASCII and never translated: earthly branches `Zi Chou Yin Mao Chen Si Wu Wei Shen You Xu Hai`, heavenly stems `Jia Yi Bing Ding Wu Ji Geng Xin Ren Gui`, palaces `Menh PhuMau PhucDuc DienTrach QuanLoc NoBoc ThienDi TatAch TaiBach TuTuc PhuThe HuynhDe`, 14 major stars `TuVi ThienCo ThaiDuong VuKhuc ThienDong LiemTrinh ThienPhu ThaiAm ThamLang CuMon ThienTuong ThienLuong ThatSat PhaQuan`, mutagens `Loc Quyen Khoa Ky`. Every key has a Vietnamese label (`name_vi`) in one table in `domain`.
- Brightness (Vietnamese 5 levels) mapped from iztro's 7: 庙→`Mieu`, 旺→`Vuong`, 得/利→`Dac`, 平/不→`Binh`, 陷→`Ham`.
- Every chart response carries `school` = `"iztro-default+vn-calendar"` (VN) or `"iztro-default"` (CN).
- Quality gates as platform: fmt, clippy `-D warnings`, `cargo deny check` (licences must pass deny.toml — engine crate included), workspace line coverage ≥ 80%.
- Images: non-root, multi-arch, `ghcr.io/<owner>/chart-svc`, `mem_limit` 64m locally, port `127.0.0.1:8082`.
- All cargo via `scripts/cargo.sh`; Node only via `docker run node:22-alpine`.

## Review Focus

1. **Births 23:00–23:59** → next day, hour `Zi`, lunar date of the next day (can change lunar month at month end). Tests in Task 3 and Task 5.
2. **South Vietnam 1959-12-31 → 1975-06-13** (zone `Asia/Ho_Chi_Minh` was UTC+8): `1970-06-01T23:30` there is 22:30 at UTC+7 → same day, hour `Hai` (not next-day Tý). Test in Task 3.
3. **Dates where VN and CN lunar calendars differ**: Tết 1985 is `1985-01-21` (VN) vs `1985-02-20` (CN); Tết 1968 is `1968-01-29` (VN, UTC+7) vs `1968-01-30` (CN); Tết 2007 is `2007-02-17` (VN) vs `2007-02-18` (CN). Same birth, different calendar → different lunar date and chart. Tests in Task 2 and Task 4.
4. **Invalid input** (`1994-02-30`, year 1899 / 2101, unknown zone `Mars/Base`, missing or bad `gender`, `calendar` not VN|CN, malformed JSON) → 400 `validation-error` naming the field; never 500. Test in Task 5.
5. **Leap lunar months**: 2023 had a leap 2nd month (solar 2023-03-22 → 2023-04-19); a birth on `2023-04-01` → `lunar.month == 2`, `lunar.leap == true`, chart equals iztro's for the same input. Tests in Task 2 and Task 4.

---

### Task 1: chart-svc skeleton

**Files:**
- Create: `services/chart-svc/{Cargo.toml, src/{main.rs, lib.rs, app.rs, config.rs, domain/mod.rs, application/mod.rs, adapters/{mod.rs, http.rs}}, tests/{architecture.rs, http_api.rs}}`
- Modify: root `Cargo.toml` (members += `services/chart-svc`, add `jiff` to workspace deps later in Task 3)

**Interfaces:**
- Consumes: `platform_core::{telemetry::init, http::{health_router, trace_layer, ApiError, ApiJson, ReadinessCheck}}`.
- Produces: `chart_svc::app::router(state: AppState) -> axum::Router` (testable in-process), `app::run(config) -> anyhow::Result<()>`; `config::Config::from_env()` (`PORT` default 8082, invalid port → error without echoing value).

- [ ] **Step 1:** Write failing tests: `http_api.rs` — `GET /health/live` 200 `{"status":"UP"}`; `GET /health/ready` 200; unknown route returns 404 problem+json with slug `not-found` (use an axum fallback; this also fixes the notify-svc parity gap noted in Plan 2 final review for this service). `architecture.rs` — `domain` imports no axum/tokio/jiff/x_iztro/serde_json (reuse notify-svc's purity test approach).
- [ ] **Step 2:** Run `scripts/cargo.sh test -p chart-svc` → FAIL (crate missing).
- [ ] **Step 3:** Implement skeleton mirroring notify-svc (`main.rs` = telemetry init + `app::run`; wiring in `app.rs`; graceful shutdown on SIGTERM/Ctrl-C).
- [ ] **Step 4:** Tests PASS; fmt, clippy, deny clean.
- [ ] **Step 5:** Commit `feat(chart): chart-svc skeleton`.

---

### Task 2: Lunar calendar (VN UTC+7 / CN UTC+8) and can chi

**Files:**
- Create: `services/chart-svc/src/domain/calendar.rs`, `services/chart-svc/tests/fixtures/lunar/README.md` (+ fixture JSON if a reference is used)

**Interfaces:**
- Produces:
  - `pub enum CalendarKind { Vn, Cn }` with `fn offset_hours(self) -> i8` (7 / 8)
  - `pub struct LunarDate { year: i32, month: u8, day: u8, leap: bool }`
  - `pub fn solar_to_lunar(date: time::Date, cal: CalendarKind) -> LunarDate`
  - `pub fn lunar_to_solar(l: LunarDate, cal: CalendarKind) -> Option<time::Date>`
  - `pub struct CanChi { stem: Stem, branch: Branch }`; `pub fn year_can_chi(lunar_year: i32) -> CanChi`, `month_can_chi(l: LunarDate) -> CanChi`, `day_can_chi(date: time::Date) -> CanChi`, `hour_branch(hour: u8) -> Branch` (23→Zi, 0→Zi, 1–2→Chou, …).

- [ ] **Step 1:** Write failing tests with exact values:
  - `solar_to_lunar(1985-01-21, Vn) == {1985,1,1,false}`; `solar_to_lunar(1985-01-21, Cn) == {1984,12,1,false}`; `solar_to_lunar(1985-02-20, Cn) == {1985,1,1,false}`.
  - `solar_to_lunar(1968-01-29, Vn) == {1968,1,1,false}`; `solar_to_lunar(1968-01-30, Cn) == {1968,1,1,false}`.
  - `solar_to_lunar(2007-02-17, Vn) == {2007,1,1,false}`; `solar_to_lunar(2007-02-18, Cn) == {2007,1,1,false}`.
  - Tết both calendars: 2023-01-22, 2024-02-10, 2025-01-29, 2026-02-17 → `{Y,1,1,false}`.
  - Leap: `solar_to_lunar(2023-04-01, Vn) == {2023,2,11,true}`; `solar_to_lunar(2023-03-22, Vn) == {2023,2,1,true}`.
  - Round trip: for every day 1900-01-31..2100-12-31 (both calendars) `lunar_to_solar(solar_to_lunar(d)) == Some(d)`.
  - Can chi: `year_can_chi(1994) == Giáp Tuất (Jia, Xu)`; `year_can_chi(2026) == Bính Ngọ (Bing, Wu)`; `day_can_chi(2000-01-01) == Mậu Ngọ (Wu, Wu)`; `hour_branch(23) == Zi`, `hour_branch(8) == Chen`.
  - **Reference sweep:** if an independent published implementation of Hồ Ngọc Đức's algorithm is usable (licence permits test use), generate `tests/fixtures/lunar/vn-1900-2100.json` (first solar day of every lunar month, both calendars) through Docker and assert equality for all entries; record source + licence in the fixture README. If none is usable, the README says so and the pinned dates above plus the round-trip are the gate.
  - The pinned values come from public Tết tables and the planner's knowledge. If the reference sweep or iztro (CN) disagrees with a pinned value, the reference wins: fix the test, and note it in the report as a plan defect.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the astronomical method (Julian day numbers, new-moon and sun-longitude series per Meeus as used by Hồ Ngọc Đức) parameterised by `offset_hours`. No lookup tables except the fixtures.
- [ ] **Step 4:** Tests PASS (sweep runs in < 5 s).
- [ ] **Step 5:** Commit `feat(chart): Vietnamese and Chinese lunar calendar`.

---

### Task 3: Birth-time normalisation (historical zones, giờ Tý)

**Files:**
- Create: `services/chart-svc/src/application/birth.rs` (uses jiff, so it lives outside `domain`)
- Modify: root `Cargo.toml` workspace deps: `jiff` with bundled tzdb (choose the feature that embeds tzdata so the distroless image needs no `/usr/share/zoneinfo`)

**Interfaces:**
- Produces: `pub struct NormalizedBirth { solar_date: time::Date, hour_branch: Branch, lunar: LunarDate, calendar: CalendarKind }`; `pub fn normalize(local: time::PrimitiveDateTime, zone: &str, cal: CalendarKind) -> Result<NormalizedBirth, BirthError>`; `BirthError::{UnknownZone, OutOfRange, Nonexistent}` (each maps to a 400 field in Task 5).

- [ ] **Step 1:** Failing tests:
  - `Asia/Ho_Chi_Minh`, `1970-06-01T23:30`, Vn → `solar_date 1970-06-01`, `hour_branch Hai` (UTC+8 → UTC+7 = 22:30).
  - `Asia/Ho_Chi_Minh`, `1994-03-12T23:10`, Vn → `solar_date 1994-03-13`, `Zi`.
  - `Asia/Ho_Chi_Minh`, `1994-03-12T08:30`, Vn → `1994-03-12`, `Chen`; `lunar == {1994,2,1,false}`.
  - `Asia/Taipei`, `1994-03-12T08:30`, Cn → offset +8 unchanged, `Chen`.
  - `America/New_York`, `2000-01-01T12:00`, Vn → converted to UTC+7 = `2000-01-02T00:00` → `solar_date 2000-01-02`, `Zi`.
  - Nonexistent local time (a DST gap, e.g. `America/New_York 2021-03-14T02:30`) → `BirthError::Nonexistent`.
  - `Mars/Base` → `UnknownZone`; `1899-12-31T12:00` → `OutOfRange`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement: resolve zone (jiff) → instant → shift to fixed `offset_hours` → if hour == 23 move to next day → `solar_to_lunar`. **Step 4:** PASS + gates.
- [ ] **Step 5:** Commit `feat(chart): birth time normalisation with historical zones`.

---

### Task 4: Engine adapter + golden tests against iztro

**Files:**
- Create: `services/chart-svc/src/domain/chart.rs` (value types + key/label tables), `src/application/{ports.rs, service.rs}`, `src/adapters/engine.rs`, `tools/fixtures/iztro/{package.json, package-lock.json, generate.mjs, README.md}`, `services/chart-svc/tests/{golden.rs, fixtures/iztro-golden.json}`

**Interfaces:**
- Produces (domain):
  - `pub struct Chart { lunar: LunarDate, year: CanChi, hour_branch: Branch, gender: Gender, cuc: Cuc { element: Element, number: u8 }, menh_chu: StarKey, than_chu: StarKey, than_palace: PalaceKey, palaces: [Palace; 12], school: &'static str }`
  - `pub struct Palace { key: PalaceKey, branch: Branch, stem: Stem, major: Vec<StarInPalace>, minor: Vec<StarInPalace>, decade: (u8, u8) }`; `pub struct StarInPalace { key: StarKey, brightness: Option<Brightness>, mutagen: Option<Mutagen> }`
  - `pub struct Flow { date: time::Date, lunar: LunarDate, day: CanChi, yearly: FlowLayer, monthly: FlowLayer, daily: FlowLayer }`; `pub struct FlowLayer { palace_branch: Branch, stem: Stem, mutagens: [(StarKey, Mutagen); 4] }`
  - `StarKey` covers the 14 majors plus the minor stars x-iztro returns (minor keys: ASCII Vietnamese name, table in `chart.rs`; unknown engine names → build-time test failure, not runtime panic).
- Produces (application): `pub trait ZiweiEngine: Send + Sync { fn chart(&self, b: &NormalizedBirth, g: Gender) -> anyhow::Result<Chart>; fn flow(&self, b: &NormalizedBirth, g: Gender, day: time::Date) -> anyhow::Result<Flow>; }`; `pub struct ChartService<E: ZiweiEngine>` with `chart(req) / daily(req, date)`.

- [ ] **Step 1 — engine choice (ruling point):** add `x-iztro` (latest 0.x). Run `scripts/cargo.sh deny check`. If its licence passes and it exposes chart-by-lunar-date (with leap flag) and horoscope/flow by date, use it. Else try the `iztro` crate under the same checks. If neither passes, STOP and report BLOCKED with findings (do not port iztro by hand in this task). Record the choice + version in `tools/fixtures/iztro/README.md`.
- [ ] **Step 2 — golden fixtures:** `generate.mjs` uses JS `iztro@2.5.8` (pinned in package-lock) inside `docker run --rm -v ...:/w -w /w node:22-alpine`, seeded PRNG, 300 cases: solar dates spread 1900–2100, both genders, all 13 iztro time indices, plus every Review Focus date. For each: input + palaces (branch, stem, major/minor star names, brightness, mutagen, decade ranges) + 五行局 + 命主/身主 + `horoscope(day)` for one fixed day per case (yearly/monthly/daily palace + mutagens). Output `iztro-golden.json` (commit it; regenerate is a documented manual step).
- [ ] **Step 3:** Failing `golden.rs`: for every case, `CN` path through `ChartService` (bypassing jiff by constructing `NormalizedBirth` from the fixture's solar date + time index) equals the fixture after key/brightness mapping; flow equals fixture. Plus VN cases: `1985-02-01T08:30` is lunar 1985-01-12 (Ất Sửu) in VN but 1984-12-12 (Giáp Tý) in CN → the VN chart must equal iztro's chart **by lunar input** for the VN lunar date (generate those expectations in the same script via iztro's `byLunar`), and must differ from the CN chart.
- [ ] **Step 4:** Implement adapter + mapping tables (Vietnamese labels: 14 majors, palaces, Tứ Hóa, cục names `Thủy nhị cục, Mộc tam cục, Kim tứ cục, Thổ ngũ cục, Hỏa lục cục`). Run → PASS with **zero** mismatches. Any mismatch = bug to fix, not to whitelist.
- [ ] **Step 5:** Commit `feat(chart): ziwei engine adapter with golden tests`.

---

### Task 5: HTTP API + contract

**Files:**
- Create: `contracts/openapi/chart-svc.yaml`, `services/chart-svc/tests/contract.rs`
- Modify: `services/chart-svc/src/adapters/http.rs`, `src/app.rs`, `tests/http_api.rs`

**Interfaces:**
- `POST /tuvi/chart` body `{ "birth": "1994-03-12T08:30", "zone": "Asia/Ho_Chi_Minh", "gender": "male"|"female", "calendar": "VN"|"CN" }` (`zone` default `Asia/Ho_Chi_Minh`, `calendar` default `VN`) → 200 chart JSON (camelCase): `school, calendar, lunar{year,month,day,leap}, yearCanChi{stem,branch,nameVi}, hourBranch, cuc{element,number,nameVi}, menhChu, thanChu, thanPalace, palaces[12]{key,nameVi,branch,stem,isBody,decade{from,to},major[{key,nameVi,brightness,mutagen}],minor[...]}`. Palaces ordered by branch starting at `Yin` (Dần) — the order the web board renders.
- `POST /tuvi/daily` body = chart body + `"date": "2026-11-25"` → 200 `{ date, lunar, dayCanChi, yearly, monthly, daily }` each layer `{palaceKey, branch, stem, mutagens[{star, mutagen}]}`. `date` range 1900–2100.
- Consumed by F1.2 (reading-tuvi) — this YAML is the contract.

- [ ] **Step 1:** Failing tests (in-process router):
  - happy path chart for `1994-03-12T08:30 Asia/Ho_Chi_Minh male VN` equals a snapshot file `tests/fixtures/snap-1994-03-12.json` produced from the golden-verified engine (byte-identical on two calls).
  - Review Focus 4 table: each bad input → 400, `type urn:problem:validation-error`, `errors[0].field` ∈ {`birth`,`zone`,`gender`,`calendar`,`date`}; malformed JSON → 400 field `body`.
  - Review Focus 1, 2, 3, 5 through HTTP (assert `lunar`, `hourBranch`).
  - `contract.rs`: responses validate against `chart-svc.yaml` (same jsonschema + yaml approach as notify-svc), with a guard test that the validator rejects a broken document.
- [ ] **Step 2:** FAIL. **Step 3:** Implement handlers (map `BirthError` → field errors; engine error → 500 "Unexpected error"). **Step 4:** PASS; coverage ≥ 80%.
- [ ] **Step 5:** Commit `feat(chart): tuvi chart and daily HTTP API`.

---

### Task 6: Image, local stack, CI, ship

**Files:**
- Create: `services/chart-svc/Dockerfile` (copy notify-svc's cargo-chef + distroless pattern; no librdkafka, so check with `ldd` whether libz is still needed), `scripts/smoke-chart.sh`
- Modify: `deploy/local/docker-compose.yml` (service `chart-svc`, profile `app`, `mem_limit: 64m`, `127.0.0.1:8082:8082`, OTLP to lgtm), `.github/workflows/ci.yml` (filter `chart: services/chart-svc/**|libs/rust/**|Cargo.toml|Cargo.lock|rust-toolchain.toml|deny.toml|.cargo/**|contracts/openapi/chart-svc.yaml`, job `chart-svc` via `_rust-service.yml`, `ci-gate.needs += chart-svc`), `.github/dependabot.yml` (docker at `/services/chart-svc`), `CLAUDE.md` (repo map + commands: chart-svc port, smoke script)

- [ ] **Step 1:** `docker build -f services/chart-svc/Dockerfile .` succeeds; runs as uid 65532; image size reported.
- [ ] **Step 2:** `docker compose ... --profile app up -d chart-svc`; `scripts/smoke-chart.sh http://localhost:8082` posts the Task 5 happy-path body and checks `palaces` length 12 and `lunar.month == 2` → `OK`; `docker stats` within 64m; `down`.
- [ ] **Step 3:** Push branch, open PR, `ci-gate` green (every CI failure → lesson per `retro`). Do NOT merge — controller merges after final review.

---

## Done criteria

- `POST /tuvi/chart` and `/tuvi/daily` live in the local stack; golden suite (300 iztro cases + VN cases) passes with zero mismatches; Review Focus 1–5 each pinned by a test.
- `contracts/openapi/chart-svc.yaml` merged — F1.2 builds against it.
- `main` CI green with `chart-svc`; `ghcr.io/<owner>/chart-svc:main` lists amd64 + arm64.
