# Plan F2.1 — astro-chart-svc: Western natal chart engine + transits

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A stateless Rust service `astro-chart-svc` turns a birth (local date-time, IANA zone, latitude, longitude, time known or not) into a tropical natal chart: 12 bodies, Asc/MC, 12 house cusps, Ptolemaic aspects. For a given day it also returns the transit facts that F2.3's free "Hôm nay" reading needs. Accuracy is proven ≤ 1′ by golden tests against JPL Horizons and a house reference set.

**Architecture:** New workspace member scaffolded with `scripts/new-service.sh --lang rust --name astro-chart-svc --port 8086`. It is hexagonal like chart-svc, with no DB or Kafka:
- `domain` (pure math): time scales and ΔT, angles and signs, nutation and obliquity, sidereal time, houses, aspects, chart value types.
- `application`: birth normalisation, an `EphemerisPort` trait, and `ChartService` (chart + transits).
- `adapters::ephemeris`: VSOP87D planets, Meeus Moon and true node, interpolated Horizons tables for Pluto and Chiron.
- `adapters::http`: the axum handlers.

**Tech Stack:** Rust workspace (existing toolchain), axum/serde/tokio via workspace deps, `jiff` (historical tz offsets, as chart-svc), `time` (chrono is banned by cargo-deny), new workspace dep `vsop87 = "3.0.0"` (MIT/Apache-2.0, no deps). Fixture generators run only in throwaway Docker containers: `node:22-alpine` for Horizons, and `python:3.13-slim` + `pyswisseph` for houses and node.

**Spec:** `docs/superpowers/specs/2026-11-28-f2-chiem-tinh-design.md` (§4.3 engine, §5 approach E1); base spec `docs/superpowers/specs/2026-11-24-fortune-platform-design.md`. Platform rules: `CLAUDE.md`, `.claude/skills/rust-service-conventions/SKILL.md`, `.claude/skills/new-service/SKILL.md`. Pattern to copy: `services/chart-svc` (Plan F1.1).

## Global Constraints

- Logs / HTTP errors / health exactly as platform (`CLAUDE.md` "Logging / observability contract").
  - 400s use slug `validation-error` with `errors: [{field, message}]`; 500 detail exactly `"Unexpected error"`.
  - Never log birth data, coordinates or the request body.
- Stateless: no DB, no Kafka; `/health/ready` is UP when the process can serve.
- Config via env:
  - `PORT` (default `8086`), `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` (default `astro-chart-svc`), `RUST_LOG` (default `info`).
  - Local compose port `127.0.0.1:8086`; `mem_limit` 64m.
- JSON field names are camelCase (as chart-svc).
- Stable keys are ASCII and never translated; there are **no labels** in the output (i18n lives in web and the knowledge base):
  - Bodies, in this fixed order: `Sun Moon Mercury Venus Mars Jupiter Saturn Uranus Neptune Pluto NorthNode Chiron`.
  - Angles: `Asc MC`.
  - Signs: `Aries Taurus Gemini Cancer Leo Virgo Libra Scorpio Sagittarius Capricorn Aquarius Pisces`.
  - Aspects: `conjunction opposition trine square sextile`.
  - House systems: `placidus whole-sign equal`.
- Coordinates: tropical, geocentric, **apparent** ecliptic longitude/latitude of date (light-time, aberration, nutation), as JPL Horizons quantity 31.
- `school` is exactly `"tropical;houses=<placidus|whole-sign|equal>;node=true;aspects=ptolemaic-v1"`. It names the system actually used, so the fallback writes `whole-sign`.
- Supported local birth years: 1900–2100 inclusive. lat ∈ [-90, 90], lon ∈ [-180, 180]. Outside these → 400.
- Birth time:
  - The zone's historical offset (jiff tz database) gives the instant.
  - Time in a DST gap → 400 field `birth`, message `"does not exist in the zone (DST gap)"`.
  - Overlap → the earlier instant.
  - No hour-branch or standard-time rules (those are tử vi only).
- `timeKnown=false`:
  - The chart is cast at 12:00 local.
  - `angles`, `houses` and every body's `house` are absent.
  - `moonSignUncertain` is true when the Moon's sign at 00:00 differs from its sign at 23:59:59 local that day.
  - Otherwise it is false. It is always present.
- Placidus is undefined when |lat| ≥ 90° − ε (true obliquity of date), or when cusp iteration does not converge. Then the response uses whole-sign houses with `houseFallback: "whole-sign"`. `houseFallback` is absent otherwise.
- Natal aspect orbs (|separation − exact| ≤ orb):

  | Aspect | Orb |
  |---|---|
  | conjunction | 8 |
  | opposition | 8 |
  | trine | 8 |
  | square | 7 |
  | sextile | 6 |

  - A pair containing Sun or Moon gets +2.
  - A pair containing NorthNode or Chiron uses a flat 3, overriding the rules above. NorthNode takes only conjunction and opposition.
  - Asc and MC exist only when `timeKnown`. Planets aspect them with all five types at orb 5, flat. Asc and MC never aspect each other, NorthNode or Chiron.
  - Each pair keeps at most one aspect (smallest orb).
  - `a` precedes `b` in the fixed order (bodies, then `Asc`, `MC`).
- `applying` is true when |separation − exact| is decreasing, using body speeds (angles have speed 0).
- Transit orbs: transiting Moon 3, other transiting bodies 2. Transiting bodies are `Moon Sun Mercury Venus Mars`; natal targets are the 10 planets Sun–Pluto plus Asc/MC when known.
- Speed: central difference over ±0.5 day, in degrees per day. `retrograde = speed < 0`. NorthNode follows the same rule.
- Rounding: every angle and speed is rounded to 4 decimals at serialisation. On amd64 the same input gives byte-identical JSON.
- Accuracy gates, run in CI (`cargo test`):
  - every body ≤ 1′ (0.016667°) vs Horizons;
  - Asc, MC and every cusp ≤ 1′ vs the house reference;
  - NorthNode ≤ 1′ vs the house reference, with the spec §4.3 fallback ladder ending at 5′ plus a lesson;
  - interpolated Pluto and Chiron ≤ 1′ at off-grid epochs.
- Quality gates as platform:
  - fmt; clippy `-D warnings` (pedantic warn via workspace lints);
  - `cargo deny check` with no `deny.toml` change: vsop87 is MIT/Apache;
  - line coverage ≥ 80%.
- No Swiss Ephemeris code, data or crate in the repo, the image or `Cargo.lock`. It only runs inside the throwaway fixture container (Task 2).
- All cargo via `scripts/cargo.sh`; Node/Python only via `docker run`.

## Review Focus

1. **0°/360° wrap**: Sun 359.95° and Moon 0.05° is a conjunction with orb 0.1. Interpolating a table across Pisces→Aries must not average to 180°. Tests are in Task 3 (`angle_diff`), Task 7 (wrap row) and Task 9.
2. **Unknown birth time**: no angles, houses or `house`. `moonSignUncertain` must be right on a day the Moon changes sign. Transits use `houseBasis: "solar"`. Tests are in Task 9 and Task 11.
3. **High latitude**: Tromsø 69.65°N → whole-sign with `houseFallback`; 66.0°N still Placidus. Southern hemisphere (Sydney −33.87°) cusps match the reference. Tests are in Task 8.
4. **DST and historical offsets**:
   - `America/New_York 2021-03-14T02:30` → 400 `birth`;
   - `2021-11-07T01:30` → the EDT instant `05:30Z`;
   - `Asia/Ho_Chi_Minh 1970-06-01T12:00` (then UTC+8) → `04:00Z`.

   Tests are in Task 4.
5. **Invalid input**: `1990-02-30`, years 1899 and 2101, zone `Mars/Base`, lat 91, lon −181, `houseSystem: "koch"`, missing `timeKnown`, malformed JSON. Each → 400 naming the field, never 500. Tests are in Task 10.

---

### Task 1: Scaffold astro-chart-svc

**Files:**
- Generated by `scripts/new-service.sh --lang rust --name astro-chart-svc --port 8086`: `services/astro-chart-svc/**`, `deploy/k8s/base/astro-chart-svc/**`, `contracts/openapi/astro-chart-svc.yaml`, plus the registration snippets (Cargo members + lock, ci.yml, `_k8s-e2e.yml`, dependabot, compose, e2e overlay).
- Modify: root `Cargo.toml` (`[workspace.dependencies] vsop87 = "3.0.0"`); `services/astro-chart-svc/Cargo.toml` (description `"Stateless Western astrology chart service"`, deps `vsop87`, `jiff`, `time`, `thiserror`; dev-deps as chart-svc).
- Create: `services/astro-chart-svc/THIRD_PARTY.md` (vsop87 crate + VSOP87 theory by Bretagnon & Francou; JPL Horizons data credit "Solar System Dynamics Group, JPL Horizons"; Meeus *Astronomical Algorithms* 2nd ed. as formula source; Espenak–Meeus ΔT, NASA).

- [ ] **Step 1:** Run the scaffold command. Expected: exit 0, files listed. Read `git diff --stat`: only the paths above.
- [ ] **Step 2:** Add the `vsop87` workspace dep. Extend `tests/architecture.rs` with `x_iztro` and `vsop87` in the forbidden list for `src/domain`. The domain is pure math; vsop87 lives in the adapter.
- [ ] **Step 3:** Run `scripts/cargo.sh test -p astro-chart-svc --locked`, `scripts/cargo.sh clippy -p astro-chart-svc --all-targets --locked -- -D warnings` and `scripts/cargo.sh deny check`. Expected: all green (the skeleton's `/v1/info` tests pass).
- [ ] **Step 4:** Commit `feat(astro-chart): scaffold astro-chart-svc`.

### Task 2: Reference fixtures (generated once, committed)

**Files:**
- Create: `tools/fixtures/astro/README.md`, which states for each file the source, the request parameters, the licence basis, "Swiss Ephemeris is used only here, never shipped", and the regeneration command.
- Create: `tools/fixtures/astro/horizons.mjs` (Node 22, built-in `fetch`; no npm deps).
- Create: `tools/fixtures/astro/swe/gen.py` and `tools/fixtures/astro/swe/requirements.txt` (`pyswisseph` pinned to an exact version).
- Generated, committed: `services/astro-chart-svc/tests/fixtures/horizons-golden.json`, `services/astro-chart-svc/tests/fixtures/swe-reference.json`, `services/astro-chart-svc/data/pluto.csv`, `services/astro-chart-svc/data/chiron.csv`.

**Interfaces (fixture formats, consumed by Tasks 5–8):**
- `horizons-golden.json`: `{"source": "...", "cases": [{"utc": "1990-07-15T07:30:00Z", "bodies": {"Sun": {"lon": 112.1234567, "lat": 0.0000123}, ...}}]}`.
  - It covers Sun, Moon, Mercury–Neptune, Pluto (`999`) and Chiron (`2060;`).
  - API: `https://ssd.jpl.nasa.gov/api/horizons.api`, `EPHEM_TYPE=OBSERVER`, `CENTER=500@399`, `QUANTITIES=31`, `TIME_TYPE=UT`, `ANG_FORMAT=DEG`, `CSV_FORMAT=YES`, `TLIST` of the epochs.
  - Epochs (61): every 40 months from 1900-01-15 to 2100-11-15, hour `(k*7) % 24`. Add these extras:
    - `1990-07-15T07:30Z`, the snapshot birth;
    - a Mercury station, `2023-04-21T08:00Z`;
    - a Moon one hour before a sign ingress, chosen from the Horizons output;
    - `2100-12-31T12:00Z`.
- `pluto.csv` / `chiron.csv`:
  - The header line is `# jde_start=2415020.5 step_days=5 source=JPL Horizons QUANTITIES=31 TIME_TYPE=TT`.
  - Then one `lon,lat` row (degrees, 7 decimals) per step from 1899-12-21 TT to 2101-01-10 TT. The table starts two steps before 1900 and ends two steps after 2100, so a 4-point Lagrange window always fits.
  - Requests use `START_TIME/STOP_TIME/STEP_SIZE='5 d'`, `TIME_TYPE=TT`.
- `swe-reference.json`: `{"source": "pyswisseph <ver>, Moshier, generated by tools/fixtures/astro/swe", "cases": [{"utc": "...", "lat": 21.0285, "lon": 105.8542, "placidus": {"asc": .., "mc": .., "cusps": [12 numbers]}, "wholeSign": {"cusps": [...]}, "equal": {"cusps": [...]}, "trueNode": 123.4567, "meanNode": 120.1}]}`.
  - 40 cases with latitudes {−45, −33.87, 0, 10.82, 21.03, 35.68, 51.51, 60.17, 66.0} × dates spread over 1900–2100.
  - One extra case at 69.65°N with `placidus: null` and whole-sign cusps.
  - Flags: `FLG_MOSEPH`, tropical. True node is ephemeris body `TRUE_NODE`.

- [ ] **Step 1:** Write `horizons.mjs`. It serialises requests (one at a time, ≤ 1 req/s) and is a pure function of its epoch list, so a rerun reproduces the same file. Run `docker run --rm -v "$PWD:/w" -w /w node:22-alpine node tools/fixtures/astro/horizons.mjs`. Expected: the 4 files exist, `horizons-golden.json` has 65 cases, and the CSV row counts equal `(stop − start)/5 + 1`.
- [ ] **Step 2:** Write `gen.py` and run `docker run --rm -v "$PWD:/w" -w /w python:3.13-slim sh -c "pip install -q -r tools/fixtures/astro/swe/requirements.txt && python -I tools/fixtures/astro/swe/gen.py"`. Expected: `swe-reference.json` with 41 cases. Check that `Cargo.lock` and `services/` contain no `swisseph`: `git grep -n -i swisseph -- services Cargo.lock` prints nothing.
- [ ] **Step 3:** Commit `test(astro-chart): Horizons and house reference fixtures` (data + generators + README).

### Task 3: Domain math — time, angles, signs, nutation, sidereal time

**Files:**
- Create: `services/astro-chart-svc/src/domain/{time.rs, angles.rs, nutation.rs}`, and register them in `domain/mod.rs`.

**Interfaces (Produces):**
- `time.rs`:
  - `pub fn jd_from_unix(secs: i64) -> f64` (JD UT);
  - `pub fn delta_t_seconds(decimal_year: f64) -> f64` (Espenak–Meeus 2006 polynomials, segments 1900–1920 … 2050–2150);
  - `pub struct Moment { pub jd_ut: f64, pub jde: f64 }` with `Moment::from_unix(secs: i64) -> Moment`.
- `angles.rs`:
  - `pub fn norm360(x: f64) -> f64`;
  - `pub fn angle_diff(a: f64, b: f64) -> f64` (signed, in (−180, 180]);
  - `pub enum Sign` (12, order as Global Constraints) with `Sign::of(lon: f64) -> (Sign, f64 /*deg in sign*/)` and `Sign::index() -> usize`.
- `nutation.rs`:
  - `pub struct Nutation { pub dpsi: f64, pub deps: f64 }` (degrees);
  - `pub fn nutation(jde: f64) -> Nutation` (IAU 1980, the full 63-term Meeus table 22.A);
  - `pub fn mean_obliquity(jde: f64) -> f64` (Meeus 22.3, Laskar);
  - `pub fn true_obliquity(jde: f64) -> f64`;
  - `pub fn apparent_sidereal_deg(m: Moment) -> f64` (Meeus 12.4 + equation of the equinoxes).

- [ ] **Step 1:** Write failing unit tests:
  - Meeus Ex. 7.a: `jd_from_unix(-386_310_816)` (1957-10-04 19:26:24 UT) == 2436116.31 (±1e-6).
  - `delta_t_seconds(2000.0)` within 0.1 of 63.86; `delta_t_seconds(1900.0)` within 0.1 of −2.79.
  - `angle_diff(359.95, 0.05) == -0.1` (±1e-9); `angle_diff(0.05, 359.95) == 0.1`.
  - `Sign::of(359.99).0 == Pisces`, `Sign::of(0.0).0 == Aries`, `Sign::of(-30.0).0 == Aquarius`.
  - Meeus Ex. 22.a (1987-04-10 0h TD, JDE 2446895.5): Δψ = −3.788″, Δε = +9.443″, ε0 = 23°26′27.407″, each ±0.01″.
  - Meeus Ex. 12.a (1987-04-10 0h UT): apparent sidereal time 13h10m46.1351s ±0.01 s.
- [ ] **Step 2:** Run `scripts/cargo.sh test -p astro-chart-svc domain::` → FAIL (not defined).
- [ ] **Step 3:** Implement the three files.
- [ ] **Step 4:** Tests pass, and the architecture test still passes.
- [ ] **Step 5:** Commit `feat(astro-chart): time scales, nutation, sidereal time`.

### Task 4: Birth normalisation

**Files:**
- Create: `services/astro-chart-svc/src/application/birth.rs`.

**Interfaces (Produces):**
- `pub struct Birth { pub local: time::PrimitiveDateTime, pub zone: String, pub lat: f64, pub lon: f64, pub time_known: bool }`.
- `pub enum BirthError { UnknownZone, Nonexistent, OutOfRange, BadLatitude, BadLongitude }` (thiserror).
- `pub fn birth_instant(b: &Birth) -> Result<i64 /*unix secs*/, BirthError>`:
  - when `time_known=false` it uses 12:00 local of `b.local.date()`;
  - year range 1900–2100 on the local date;
  - gap → `Nonexistent`; overlap → earlier, via `jiff::tz::TimeZone::to_ambiguous_zoned_datetime`, as `services/chart-svc/src/application/birth.rs`.
- `pub fn local_day_bounds(date: time::Date, zone: &str) -> Result<(i64, i64), BirthError>`: unix secs of 00:00:00 and 23:59:59 local. Used by `moonSignUncertain` and by transits.

- [ ] **Step 1:** Write failing tests, with all Review Focus 4 cases asserting exact unix seconds:
  - `America/New_York 2021-03-14T02:30` → `Nonexistent`;
  - `2021-11-07T01:30` → `2021-11-07T05:30:00Z`;
  - `Asia/Ho_Chi_Minh 1970-06-01T12:00` → `1970-06-01T04:00:00Z`;
  - `Europe/London 1990-07-15T12:00` → `11:00Z`;
  - `time_known=false` with local `08:13` → instant of 12:00 local;
  - `1899-12-31`, `2101-01-01` → `OutOfRange`; `Mars/Base` → `UnknownZone`; lat 91 → `BadLatitude`; lon −181 → `BadLongitude`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(astro-chart): birth instant from local time and zone`.

### Task 5: Ephemeris port + Sun and planets (VSOP87D)

**Files:**
- Create: `services/astro-chart-svc/src/domain/body.rs`: `pub enum Body` (12, fixed order) with `Body::ALL`, and `pub struct Ecliptic { pub lon: f64, pub lat: f64 }`.
- Create: `services/astro-chart-svc/src/application/ports.rs`: `pub trait EphemerisPort: Send + Sync { fn apparent(&self, body: Body, m: Moment) -> anyhow::Result<Ecliptic>; }`.
- Create: `services/astro-chart-svc/src/adapters/ephemeris/{mod.rs, planets.rs}`: `pub struct EphemerisAdapter` (implements `EphemerisPort`; Moon, NorthNode, Pluto and Chiron arms return `Err` until Tasks 6–7).
- Test: `services/astro-chart-svc/tests/golden_horizons.rs`, with a helper `fn assert_close(body, case, got, want)` that prints body, utc and the difference in arcseconds on failure.

**Approach for `planets.rs`:** the full Meeus ch.33 procedure.
1. VSOP87D heliocentric spherical coordinates of date for Earth and the planet, via the `vsop87::vsop87d` functions.
2. Geocentric rectangular coordinates, with a light-time iteration (τ = 0.0057755183·Δ days, until the change is < 1e-9 day).
3. FK5 correction (Meeus 32.3).
4. Aberration (Meeus 23.2, Ron–Vondrák not required).
5. Add Δψ to the longitude.

For the Sun: Earth + 180°, β negated, the same FK5/aberration/nutation steps.

- [ ] **Step 1:** Write the failing golden test: for every case in `horizons-golden.json` and each of Sun, Mercury, Venus, Mars, Jupiter, Saturn, Uranus and Neptune, `|angle_diff(got.lon, want.lon)| ≤ 1.0/60.0` and `|got.lat − want.lat| ≤ 1.0/60.0`. Also log the maximum error per body to stdout (`--nocapture` shows it; the PR description quotes it).
- [ ] **Step 2:** Run `scripts/cargo.sh test -p astro-chart-svc --test golden_horizons` → FAIL.
- [ ] **Step 3:** Implement `body.rs`, `ports.rs` and `planets.rs`.
- [ ] **Step 4:** Run → PASS for these 8 bodies, and record the maximum errors. If any body exceeds 1′, stop and switch to spec fallback E2 (port of Astronomy Engine). Record that in a lesson. Do not loosen the threshold.
- [ ] **Step 5:** Commit `feat(astro-chart): Sun and planets from VSOP87D`.

### Task 6: Moon and lunar nodes

**Files:**
- Create: `services/astro-chart-svc/src/adapters/ephemeris/moon.rs`.
- Modify: `adapters/ephemeris/mod.rs` (Moon and NorthNode arms).
- Test: extend `tests/golden_horizons.rs` (Moon) and create `tests/golden_swe.rs` (node part).

**Interfaces (Produces):**
- `pub fn moon_geocentric(jde: f64) -> (f64 /*lon*/, f64 /*lat*/, f64 /*dist km*/)`: Meeus ch.47, tables 47.A/47.B in full, with the A1/A2/A3 and E corrections.
- `pub fn moon_apparent(jde) -> Ecliptic`: adds Δψ.
- `pub fn mean_node(jde: f64) -> f64`: Meeus 47.7.
- `pub fn true_node(jde: f64) -> f64`: the osculating node. Take the Moon's geocentric position r and velocity v (central difference ±0.05 day of `moon_geocentric`, converted to rectangular ecliptic of date). Then h = r × v, and node longitude = atan2(h_x, −h_y), normalised, plus Δψ.

- [ ] **Step 1:** Write failing tests:
  - Moon vs Horizons for every case, ≤ 1′ in lon and lat;
  - `trueNode` vs `swe-reference.json` for every case, ≤ 1′;
  - `meanNode` vs reference ≤ 0.5′.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS. If only `trueNode` exceeds 1′, follow spec §4.3 in order:
  1. Replace it with mean node plus the main periodic terms of the true node.
  2. If that still exceeds 1′, set the NorthNode threshold to 5′ in the test, with a comment citing the spec, and write `docs/lessons/2026-..-true-node-accuracy.md`.
- [ ] **Step 5:** Commit `feat(astro-chart): Moon (Meeus 47) and true node`.

### Task 7: Pluto and Chiron from Horizons tables

**Files:**
- Create: `services/astro-chart-svc/src/adapters/ephemeris/table.rs`.
- Modify: `adapters/ephemeris/mod.rs` (Pluto and Chiron arms load `include_str!("../../../data/{pluto,chiron}.csv")` once via `OnceLock`).

**Interfaces (Produces):**
- `pub struct Table { jde_start: f64, step: f64, rows: Vec<(f64, f64)> }`.
- `Table::parse(text: &str) -> Result<Table, TableError>` (header as in Task 2; any malformed row → error).
- `Table::at(&self, jde: f64) -> Result<Ecliptic, TableError>`:
  - 4-point Lagrange on the two rows on each side;
  - longitudes unwrapped across 360° before interpolating, result through `norm360`;
  - `OutOfRange` outside the table.

- [ ] **Step 1:** Write failing tests:
  - unit test of a synthetic 6-row table crossing 359°→1° (`at` mid-way ≈ 0.0, not 180);
  - parse error on a bad row;
  - `OutOfRange` before `jde_start + step`;
  - golden: Pluto and Chiron vs `horizons-golden.json` (off-grid UT epochs, converted to JDE with ΔT) ≤ 1′.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(astro-chart): Pluto and Chiron interpolated from JPL Horizons`.

### Task 8: Angles and houses

**Files:**
- Create: `services/astro-chart-svc/src/domain/houses.rs`.
- Test: `tests/golden_swe.rs` (houses part) plus unit tests in `houses.rs`.

**Interfaces (Produces):**
- `pub enum HouseSystem { Placidus, WholeSign, Equal }`, with `as_key()` → `placidus|whole-sign|equal` and `parse(&str) -> Option<Self>`.
- `pub struct Angles { pub asc: f64, pub mc: f64 }`.
- `pub fn angles(ramc: f64, eps: f64, lat: f64) -> Angles`. ramc = apparent sidereal time + geographic longitude, in degrees.
- `pub enum HouseError { Undefined }`.
- `pub fn cusps(sys: HouseSystem, ramc: f64, eps: f64, lat: f64, a: Angles) -> Result<[f64; 12], HouseError>`:
  - Placidus: iterative semi-arc for cusps 11, 12, 2, 3 (start from the equal-house guess, iterate until the change is < 1e-7°, max 50 iterations, else `Undefined`); opposite cusps +180°; `Undefined` when |lat| ≥ 90 − eps.
  - WholeSign: cusp i = 30·sign(asc) + 30·(i−1).
  - Equal: asc + 30·(i−1).

- [ ] **Step 1:** Write failing tests:
  - golden: every `swe-reference.json` case, Asc, MC and the 12 cusps of each system ≤ 1′;
  - properties over the same cases: cusp[0]==asc and cusp[9]==mc for Placidus; cusp[i+6]==cusp[i]+180 (mod 360) for all systems; cusps strictly increasing after unwrapping from cusp[0];
  - lat 66.0 → Placidus `Ok`; lat 69.65 → `Err(Undefined)`; lat −69.65 → `Err(Undefined)`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(astro-chart): Asc, MC and Placidus/whole-sign/equal houses`.

### Task 9: Aspects and chart assembly

**Files:**
- Create: `services/astro-chart-svc/src/domain/aspects.rs` and `services/astro-chart-svc/src/domain/chart.rs` (serde `Serialize` value types).
- Create: `services/astro-chart-svc/src/application/service.rs`.

**Interfaces (Produces):**
- `aspects.rs`:
  - `pub enum Point { Body(Body), Asc, MC }`, with the fixed order;
  - `pub enum AspectKind { Conjunction, Opposition, Trine, Square, Sextile }` (exact 0/180/120/90/60);
  - `pub struct PointPos { pub point: Point, pub lon: f64, pub speed: f64 }`;
  - `pub struct Aspect { pub a: Point, pub b: Point, pub kind: AspectKind, pub orb: f64, pub applying: bool }`;
  - `pub fn natal_aspects(points: &[PointPos]) -> Vec<Aspect>`, sorted by (a, b) order;
  - `pub fn transit_aspects(transiting: &[PointPos], natal: &[PointPos]) -> Vec<Aspect>` (`a` = transiting, `b` = natal; transit orbs).
- `chart.rs` (wire shape, camelCase):

  ```
  Chart { school, instantUtc, timeKnown, houseSystem, houseFallback?, moonSignUncertain,
          bodies: [BodyOut { key, lon, lat, sign, deg, speed, retrograde, house? }],
          angles?: { asc: AngleOut { lon, sign, deg }, mc: AngleOut },
          houses?: [HouseOut { house: 1..12, cusp, sign }],
          aspects: [AspectOut { a, b, type, orb, applying }] }
  ```

  A body's `house` is the cusp interval containing its longitude, handling the wrap.
- `service.rs`:
  - `pub struct ChartRequest { pub birth: Birth, pub house_system: HouseSystem }`;
  - `pub enum ChartError { Birth(BirthError), Ephemeris(#[source] anyhow::Error) }`;
  - `pub struct ChartService { eph: Arc<dyn EphemerisPort> }`, with `new` and `pub fn chart(&self, req: &ChartRequest) -> Result<Chart, ChartError>`.

- [ ] **Step 1:** Write failing unit tests for `aspects.rs`, each with exact numbers:
  - Sun 10° / Mars 19.5° → conjunction, orb 9.5 (8 + 2 for the Sun); Sun 10° / Mars 20.5° → none;
  - Sun 10° / Moon 107.5° → square, orb 7.5;
  - Venus 0° / Mars 7.9° → conjunction; Venus 0° / Mars 8.1° → none;
  - Sun 359.95° / Moon 0.05° → conjunction orb 0.1;
  - NorthNode 10° / Venus 12.9° → conjunction; NorthNode 10° / Venus 100° → none (square not allowed for the node);
  - Chiron 10° / Moon 72.9° → sextile (flat 3, the Moon's +2 does not apply); Chiron 10° / Moon 74° → none;
  - Asc 0° / Venus 94.9° → square; Asc / MC → never;
  - applying: Venus 0° speed 1.2, Mars 7° speed 0.5 → conjunction, `applying == true`; Venus 7° speed 1.2, Mars 0° speed 0.5 → `applying == false`.
- [ ] **Step 2:** Write failing `ChartService` tests with a hand-written `FakeEphemeris` (fixed longitudes). They check:
  - `timeKnown=false` → `angles`, `houses` and every `house` are `None`;
  - a fake where the Moon is 29.9° Gemini at 00:00 and 0.5° Cancer at 23:59:59 → `moonSignUncertain == true`;
  - lat 69.65 Placidus → `houseFallback == Some("whole-sign")` and `school` contains `houses=whole-sign`;
  - Ephemeris `Err` → `ChartError::Ephemeris`.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4:** Implement `aspects.rs`, `chart.rs` and `service.rs`. Order: instant → `Moment` → bodies with speeds → sidereal time, angles and cusps (if known) → houses per body → aspects → rounding to 4 decimals.
- [ ] **Step 5:** Run → PASS, then commit `feat(astro-chart): natal chart assembly and Ptolemaic aspects`.

### Task 10: HTTP `POST /astro/chart` + contract

**Files:**
- Modify: `services/astro-chart-svc/src/adapters/http.rs` (remove the scaffold `/v1/info` route and its tests), `src/app.rs` (wire `EphemerisAdapter` → `ChartService`).
- Modify: `contracts/openapi/astro-chart-svc.yaml`.
  - Request: `ChartRequest { birth: "YYYY-MM-DDTHH:MM", zone, lat, lon, timeKnown (required), houseSystem (default placidus) }`.
  - Response: `Chart` exactly as Task 9, every object `additionalProperties: false`.
  - `400` field enum: `body, birth, zone, lat, lon, timeKnown, houseSystem`.
- Test: `tests/http_api.rs`, `tests/contract.rs`, `tests/snapshot.rs`, `tests/fixtures/snap-1990-07-15.json`.

- [ ] **Step 1:** Write failing tests:
  - Each Review Focus 5 input → 400 `validation-error` with `errors[0].field` as named. A DST gap → field `birth`, message `"does not exist in the zone (DST gap)"`.
  - The happy path validates against the OpenAPI schema (`jsonschema`, as chart-svc's `contract.rs`).
  - Snapshot: `{"birth":"1990-07-15T14:30","zone":"Asia/Ho_Chi_Minh","lat":21.0285,"lon":105.8542,"timeKnown":true}` equals `snap-1990-07-15.json` byte for byte, under `#[cfg(target_arch = "x86_64")]`. Calling twice gives identical bytes on every arch.
  - The ephemeris failure path → 500 with detail `"Unexpected error"`, and the log has `error_type`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the handler, its validation and the contract. Generate the snapshot once from the implementation, then check its bodies against `horizons-golden.json`'s `1990-07-15T07:30:00Z` case (≤ 1′) before committing it.
- [ ] **Step 4:** Run the full `scripts/cargo.sh test -p astro-chart-svc --locked` → PASS.
- [ ] **Step 5:** Commit `feat(astro-chart): POST /astro/chart`.

### Task 11: `POST /astro/transits`

**Files:**
- Modify: `application/service.rs` (`transits`), `domain/chart.rs` (`Transits`), `adapters/http.rs`, `contracts/openapi/astro-chart-svc.yaml`.
- Test: `tests/http_api.rs` (transit cases), unit tests in `service.rs`.

**Interfaces (Produces):**
- Request: `{ birth: ChartRequest fields (birth, zone, lat, lon, timeKnown, houseSystem), date: "YYYY-MM-DD", viewerZone: IANA }`.
- `pub fn transits(&self, req: &TransitRequest) -> Result<Transits, ChartError>`:
  - the natal chart is recomputed from `birth`;
  - the transit moment is 12:00 of `date` in `viewerZone`.
- Wire shape:

  ```
  Transits { date, moon: { sign, deg, house, houseBasis: "natal"|"solar", phase },
             moonIngress?: { at: "HH:MM", sign },
             aspects: [AspectOut] }
  ```

  - `phase` is one of 8 keys `new waxing-crescent first-quarter waxing-gibbous full waning-gibbous last-quarter waning-crescent`, chosen by Moon − Sun elongation in 45° bins starting at 0 = `new`, each bin centred (−22.5..22.5 → new).
  - `houseBasis`: when `timeKnown=false`, the house comes from whole-sign houses starting at the natal Sun's sign.
  - `moonIngress`: the local time when the Moon enters a new sign within that local day, found by bisection and truncated to the minute.

- [ ] **Step 1:** Write failing tests:
  - known `timeKnown=false` → `houseBasis == "solar"`;
  - a FakeEphemeris day where the Moon crosses 90° at 15:20 viewer-local → `moonIngress == {at: "15:20", sign: "Cancer"}`;
  - elongation 22.4 → `new`, 22.6 → `waxing-crescent`, 180 → `full`;
  - transit Moon 3° orb (Moon 10° vs natal Venus 13.1° → none, 12.9° → conjunction);
  - invalid `date` (`2026-02-30`) → 400 field `date`; unknown `viewerZone` → 400 field `viewerZone`;
  - the contract validates.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(astro-chart): POST /astro/transits for daily facts`.

### Task 12: Smoke, kind e2e, docs

**Files:**
- Create: `scripts/smoke-astro-chart.sh` (curl + grep/sed only, style of `scripts/smoke-chart.sh`). It POSTs the snapshot body and asserts:
  - 200;
  - `"key"` occurs 12 times inside `bodies`;
  - `"cusp"` occurs 12 times;
  - `"school":"tropical;houses=placidus;node=true;aspects=ptolemaic-v1"`;
  - then prints `OK`.
- Modify:
  - `scripts/k8s-e2e.sh`: a port-forward for `svc/astro-chart-svc` on 8086, and `run smoke-astro-chart http://localhost:8086`;
  - `scripts/e2e-observability.sh`: add `astro-chart-svc` to the RED job list;
  - `CLAUDE.md`: Repo map `services/{…, astro-chart-svc (Rust)}`, a Commands line for `scripts/smoke-astro-chart.sh http://localhost:8086`, and the local memory budget line if compose changed it.
- Test: `scripts/test/*.test.sh` stays green (shellcheck in CI job `ops-scripts`).

- [ ] **Step 1:** Write the smoke script. Run it against `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build --no-deps astro-chart-svc` → `OK`, then `down`. This is light: one Rust container.
- [ ] **Step 2:** Make the k8s-e2e, observability and CLAUDE.md edits. Run `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh` → green.
- [ ] **Step 3:** Run the full verification and read the output:
  - `scripts/cargo.sh fmt --check`;
  - `scripts/cargo.sh clippy --workspace --all-targets --locked -- -D warnings`;
  - `scripts/cargo.sh deny check`;
  - `scripts/cargo.sh llvm-cov -p astro-chart-svc --fail-under-lines 80 --ignore-filename-regex '(main\.rs|/tests/)'`.
- [ ] **Step 4:** Commit `chore(astro-chart): smoke + kind e2e + docs`. Then open the PR with the per-body maximum errors from Tasks 5–8 in its description. Merge on green CI (k8s-e2e runs because `scripts/**` changed). Prod registration (`--prod`) is **not** part of F2.1: it waits for F2.5 (spec §6).
