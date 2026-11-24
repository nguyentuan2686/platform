# iztro golden fixture

`generate.mjs` writes `services/chart-svc/tests/fixtures/iztro-golden.json`, which
`services/chart-svc/tests/golden.rs` checks chart-svc against: every case must match,
field by field (palace, stem, branch, decade, major and minor stars with brightness and
Tứ Hóa, cục, mệnh chủ, thân chủ, thân palace, year can chi, and the yearly / monthly /
daily flow layers).

## Engine (chart-svc)

- Crate: [`x-iztro`](https://crates.io/crates/x-iztro) **0.9.0**, licence **MIT**
  (passes `cargo deny check` with the existing allow-list). A Rust port of JS iztro
  **2.6.3**, default configuration. It casts by lunar date with a leap flag
  (`by_lunar` + `LeapMonth`) and computes the flow of a date (`get_horoscope`).
- Chosen over the `iztro` crate (0.9.0) because it is the first choice of the plan and
  meets every requirement; `iztro` was not evaluated further.

## Reference

- Package: [`iztro`](https://www.npmjs.com/package/iztro) **2.6.3** by SylarLong, licence
  **MIT**, pinned with its dependencies in `package-lock.json`.
- Output names are zh-CN; the test maps them to chart-svc keys. Default config,
  `fixLeap = true` (iztro's default). Seeded PRNG, no clock: the output is reproducible.

## Cases

- 300 random CN cases, solar dates 1900-2100, both genders, time indices 0-11, each with
  the flow of a random later day (`bySolar` + `horoscope(day, 0)`).
- Review Focus dates on the CN path: 1994-03-12, 1985-02-01, Tết 1968 / 1985 / 2007 in
  both calendars, the 2023 leap 2nd month (both halves), 1970-06-01 at hour Hợi.
- VN cases: chart-svc feeds the engine its own VN lunar date, so the expectation is
  iztro's chart **by lunar input** (`byLunar`). The test re-derives the lunar date from
  the solar date with chart-svc's VN calendar. Includes 1985-02-01 (VN 1985-01-12 Ất
  Sửu, CN 1984-12-12 Giáp Tý; the charts differ) and the Tết and leap-month dates.
- Late Zi (ruling R3): iztro's time index 12 (late Zi) is never used. A 23:30 birth
  must equal the next day's index-0 chart, across a month start, Tết eve and a year end.

## Exclusions and mappings

- **CN calendar mismatches.** chart-svc's CN calendar (Hồ Ngọc Đức's method at UTC+8)
  and the official Chinese calendar iztro uses start a lunar month on different days on
  1914-11-18, 1916-02-04, 1920-11-11, 2057-09-28 and 2097-08-07 (see
  `tools/fixtures/lunar/README.md`). The month around each date is kept out of the CN
  cases (birth and flow dates more than 40 days away). For every other CN case the test
  asserts chart-svc's lunar date equals iztro's.
- **VN-only leap months.** Vietnam had leap months China did not (for example 1985/2
  and 1987/7). iztro ignores the leap flag for a
  month its calendar does not have as leap. chart-svc charts such a month the way iztro
  charts a real leap month with `fixLeap`: days 1-15 as month m, days 16-30 as month
  m + 1. The generator checks this equivalence on every real leap month 1900-2099
  (`leapEquivalenceChecks`), and the two 1985 VN cases carry `engineLunar`, the plain
  month given to iztro.
- **Day 30 missing in the engine's month.** When a VN (or CN mismatch) month has 30 days
  but the engine's month has 29, chart-svc feeds the same month and day of a year 60·k
  years away (same year can chi, so the same chart). `engine::tests` proves every VN and
  CN day 1900-2100 gets an engine date.
- **Flows on the VN calendar.** The flow day is also fed to the engine as the date whose
  engine lunar date equals the day's VN lunar date (same mappings as above), and the
  daily stem and its Tứ Hóa are set from the real day's can chi. iztro has no VN
  calendar, so `golden.rs` checks VN flows against iztro's flow arithmetic over
  2029-12..2030-03 (Tết 2030 differs between the calendars) instead of a fixture.
- **Month can chi** is not compared (iztro may use solar terms); the chart fields above
  do not depend on it.

## Why 2.6.3, not 2.5.8

The plan first pinned iztro 2.5.8. x-iztro 0.9.0 ports 2.6.3, and upstream corrected
three brightness values in the Dậu (酉) palace after 2.5.8; diffing `lib/data/stars.js`
of the two versions shows no other brightness or Tứ Hóa table change:

| Star | 2.5.8 | 2.6.3 / x-iztro | chart-svc (5 levels) |
|---|---|---|---|
| 太阳 Thái Dương | 陷 | 平 | Bình |
| 太阴 Thái Âm | 不 | 旺 | Vượng |
| 七杀 Thất Sát | 庙 | 旺 | Vượng |

The fixture therefore comes from the version the engine ports, and `golden.rs` has no
exceptions: every field of every case must match.

## Regenerate

Node only through Docker, from this directory (Git Bash):

```
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/w" -w /w node:22-alpine npm ci --ignore-scripts
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/w" -w /w node:22-alpine node generate.mjs \
  > ../../../services/chart-svc/tests/fixtures/iztro-golden.json
```

On Linux or macOS, use `$(pwd)` and drop `MSYS_NO_PATHCONV=1`. Generation takes about
3-4 minutes on the dev machine (mostly the leap-month equivalence check). Running it
twice gives byte-identical output.
