# Plan F1.1b — chart-svc: phái Việt cho lá số lịch Việt

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Charts with `calendar=VN` follow the Vietnamese school on every point where the F1.2a cross-check found iztro and Vietnamese practice disagree (brightness, Tứ Hóa of Canh/Nhâm, Khôi/Việt, Hỏa/Linh, Giải Thần, Phá Toái, Vietnamese-only stars), proven by matching `lasotuvi` on 50 charts; `calendar=CN` charts are unchanged.

**Architecture:** x-iztro keeps computing the base chart. A pure domain module `domain/vn_school.rs` holds the Vietnamese tables and placement rules and rewrites a `Chart` (and the Tứ Hóa of flow layers) when the calendar is VN. The engine adapter calls it after mapping; the CN path never touches it. Tables are data (match arms / const arrays) with their source cited.

**Tech Stack:** Rust chart-svc (F1.1/F1.2a), Python `lasotuvi` fixture generator (Docker, existing `tools/fixtures/lasotuvi`).

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §5.5 (Vietnam → Vietnamese school), §11 (Vietnamese school backlog), decision by Tuấn 2026-11-24 ("theo đề xuất phái Việt dùng cho người Việt"). Evidence: `docs/tuvi/vn-school-diff.md`. Builds on main 62dbd96.

## Global Constraints

- Vietnamese-school rules apply **only** when `calendar=VN`; every `calendar=CN` output stays byte-identical to today (golden vs iztro unchanged).
- Reference for Vietnamese rules: `lasotuvi` at the pinned commit `ace8379a3ea033674e8a9096f5b98a9e2041eace` (MIT). Table data taken from it is credited in `services/chart-svc/THIRD_PARTY.md` (name, URL, commit, MIT licence text).
- **Not** adopted from lasotuvi (kept as iztro): the Thai/Dưỡng order of the Tràng Sinh ring (lasotuvi defect), Thiên Quý (lasotuvi marks its own rule uncertain), Thiên Trù, the leap-month rule (keep iztro's split at day 15 — common Vietnamese practice).
- New `school` string for VN charts: `"vn+vn-calendar;hour=local-standard;ty23=next-day;leap=split15"`; CN unchanged (`"iztro-default;hour=local-standard;ty23=next-day"`).
- New star keys follow the F1.2a rule (ASCII fold of the Vietnamese label): `DaoHoa, ThienGiai, DiaGiai, ThienY, QuocAn, DuongPhu, VanTinh, LuuHa, DauQuan, ThienLa, DiaVong`. Additive contract change only.
- Brightness on the Vietnamese 5-level scale `Mieu Vuong Dac Binh Ham`; stars lasotuvi gives a level that chart-svc lacked today (Địa Không, Địa Kiếp, Thiên Mã) get it for VN; Thiên Phủ (no row in lasotuvi) keeps the iztro-derived level.
- reading-tuvi keeps a single corpus: knowledge `school` stays `"iztro-default"`, and the chart-school → knowledge-school mapping treats both `iztro-default…` and `vn…` as `"iztro-default"` (doctrine is about star meanings, not placement rules).
- All cargo via `scripts/cargo.sh`; Python only in `docker run python:3.12-alpine`; lasotuvi code runs only in the container.

## Review Focus

1. **CN regression** — any CN chart changing. The existing iztro golden (333 cases) must pass unchanged for CN cases; VN cases in that golden are re-baselined only for the overridden fields.
2. **Flow layers keep the old Tứ Hóa** — the daily/monthly/yearly layers of a VN chart must use the Vietnamese Tứ Hóa table too (a Canh day gives Kỵ Thái Âm); test in Task 2.
3. **Duplicate placement** — a star moved by a VN rule must disappear from its iztro palace (no star in two palaces); test asserts each star key appears exactly once per chart.
4. **Hàm Trì vs Đào Hoa** — if iztro's Hàm Trì sits where Vietnamese Đào Hoa sits in all 50 cases, VN charts show it as `DaoHoa` (relabel, not a second star); otherwise both are kept. Decided by data in Task 3, recorded in the report.
5. **reading-tuvi with a VN chart** — the knowledge-school mapping must still find records (a basic reading for a VN chart is not all `missing`); test in Task 4.

---

### Task 1: VN brightness table and Tứ Hóa table

**Files:** Create `services/chart-svc/src/domain/vn_school.rs`, `services/chart-svc/THIRD_PARTY.md`; modify `src/domain/mod.rs`, `src/adapters/engine.rs`, `tests/vn_crosscheck.rs`, `tools/fixtures/lasotuvi/generate.py` (+ regenerated fixture: add per-star brightness and Tứ Hóa if not already emitted).

**Interfaces:**
- `pub fn vn_brightness(star: StarKey, branch: Branch) -> Option<Brightness>` (14 major + the minor stars lasotuvi rates).
- `pub fn vn_tu_hoa(stem: Stem) -> [(StarKey, Mutagen); 4]` in Lộc, Quyền, Khoa, Kỵ order. Canh = `[ThaiDuong Loc, VuKhuc Quyen, ThienDong Khoa, ThaiAm Ky]`; Nhâm Khoa = `ThienPhu`; other stems equal iztro's (assert all 10 in tests).
- `pub fn apply_vn_school(chart: &mut Chart, flows: Option<&mut Flow>)` — entry point extended by Tasks 2–3.

- [ ] **Step 1: Failing tests:** unit tests for `vn_tu_hoa` (all 10 stems, exact arrays) and `vn_brightness` spot values copied from lasotuvi's `dacTinhSao` (e.g. Thiên Cơ at Dậu = `Mieu`; plus one per major star); `vn_crosscheck.rs` turns brightness and Hóa Khoa/Kỵ from "reported" into **must-match** for all 50 cases.
- [ ] **Step 2:** Implement the tables (data transcribed from lasotuvi, cited in a module doc comment and THIRD_PARTY.md); `engine.rs` calls `apply_vn_school` when calendar is VN; VN `school` string changes per Global Constraints; regenerate the HTTP snapshot.
- [ ] **Step 3:** Tests PASS; iztro golden still green for CN cases (adjust the golden harness so VN cases skip the overridden fields only); fmt/clippy/deny clean. **Commit** `feat(chart): Vietnamese brightness and Tứ Hóa tables`.

---

### Task 2: Tứ Hóa of flow layers + Khôi/Việt + Hỏa/Linh + Giải Thần + Phá Toái

**Files:** modify `src/domain/vn_school.rs`, `src/adapters/engine.rs`, tests.

- [ ] **Step 1: Failing tests:** `/tuvi/daily` for a VN chart on a Canh day (`2026-11-27` is not guaranteed Canh — compute: pick the first date ≥ 2026-11-24 whose day stem is Canh via `day_can_chi`, assert it in the test) has daily mutagens `[ThaiDuong Loc, VuKhuc Quyen, ThienDong Khoa, ThaiAm Ky]`, and the CN chart for the same birth keeps iztro's. Must-match in `vn_crosscheck.rs` for Thiên Khôi, Thiên Việt, Hỏa Tinh, Linh Tinh, Giải Thần, Phá Toái over all 50 cases. Each star appears exactly once per chart (Review Focus 3).
- [ ] **Step 2:** Implement rules as pure functions of (year can chi, lunar month, hour branch, gender) following lasotuvi's `timThienKhoi`, `timHoaLinh`, Giải Thần with Phượng Các, Phá Toái cycle Tỵ/Dậu/Sửu by year branch mod 3; move stars between palaces in `apply_vn_school`.
- [ ] **Step 3:** PASS, gates clean. **Commit** `feat(chart): Vietnamese Khôi/Việt, Hỏa/Linh, Giải Thần, Phá Toái and flow Tứ Hóa`.

---

### Task 3: Vietnamese-only stars

**Files:** modify `src/domain/chart.rs` (new `AdjStarKey` variants + labels), `src/domain/vn_school.rs`, `src/adapters/http.rs`, `contracts/openapi/chart-svc.yaml`, tests, `generate.py` (emit these stars), `docs/tuvi/vn-school-diff.md` (mark adopted / kept rows).

- [ ] **Step 1: Failing tests:** must-match in `vn_crosscheck.rs` for `DaoHoa, ThienGiai, DiaGiai, ThienY, QuocAn, DuongPhu, VanTinh, LuuHa, DauQuan, ThienLa, DiaVong` over 50 cases; Thiên La always at `Chen`, Địa Võng always at `Xu`; CN charts contain none of these keys.
- [ ] **Step 2:** Implement placement per lasotuvi; decide Hàm Trì vs Đào Hoa by the data (Review Focus 4) and record it in the report and in `vn-school-diff.md`; update the YAML enum (additive).
- [ ] **Step 3:** PASS, gates clean, contract test green. **Commit** `feat(chart): Vietnamese-only stars for VN charts`.

---

### Task 4: reading-tuvi school mapping + PR

**Files:** modify reading-tuvi's chart-school → knowledge-school function (introduced in F1.2a; find it via `iztro-default` usages) and its test; recapture `src/test/resources/chart-fixture.json` and `daily-fixture.json` from the new chart-svc (VN chart); `CLAUDE.md` if the smoke expectations change.

- [ ] **Step 1: Failing test:** knowledge school for `"vn+vn-calendar;hour=local-standard;ty23=next-day;leap=split15"` is `"iztro-default"`; `ReadingFlowIT` with the recaptured VN fixture still renders 17 sections with seed frames (not all `missing`).
- [ ] **Step 2:** Implement; `mvn -q -pl services/reading-tuvi -am verify` green; local smoke (`postgres`, `chart-svc`, `reading-tuvi`, `--no-deps`): `smoke-chart.sh` and `smoke-reading.sh` OK; stack down.
- [ ] **Step 3:** Push, open PR, `ci-gate` green (failure → lesson). Do NOT merge. **Commit** `feat(reading): VN school charts map to the shared corpus`.
