# Lunar calendar reference fixture

`vn-1900-2100.json` lists the first solar day of every lunar month whose first day
falls in 1900-01-01..2100-12-31, for both calendars: `vn` (meridian UTC+7) and `cn`
(UTC+8). Each row is `[solar date, lunar year, lunar month, leap]`.
`tests/lunar_reference.rs` asserts that `solar_to_lunar` and `lunar_to_solar` agree
with every row.

## Source

- Package: [`date-chinese`](https://www.npmjs.com/package/date-chinese) **2.2.1**
  by commenthol (<https://github.com/commenthol/date-chinese>), licence **MIT**.
- Dependency: `astronomia` 4.3.0 (MIT), which provides the VSOP87 solar
  position and the Meeus new-moon algorithm.
- Why this one: it is an independent implementation of the same rules (a month
  starts on the local day of the new moon, month 11 holds the winter solstice, and
  in a 13-month year the first month without a major solar term is the leap month).
  It uses its own, higher-precision ephemeris, not Hồ Ngọc Đức's code.
- Adjustment: the generator overrides `timeshiftUTC` to a fixed UTC+7 or UTC+8.
  Upstream `CalendarVietnamese` uses UTC+8 before 1968 and `CalendarChinese` uses
  Beijing local mean time before 1929. chart-svc defines VN as UTC+7 and CN as UTC+8
  for all years.

Rejected: npm ports of Hồ Ngọc Đức's own `amlich` code (for example
`cky-lunar-calendar`, which declares MIT). They carry his original notice, which
permits only personal, non-commercial use, and they are not independent of the
algorithm under test.

## Known divergences (asserted exactly)

chart-svc implements Hồ Ngọc Đức's method with Meeus's truncated series. This
reference uses a full ephemeris, so the two disagree where a new moon falls within
about a minute of local midnight, or where the leap month's placement depends on such
a margin. `tests/lunar_reference.rs` lists these rows, and the test fails if any other
row differs or if a listed row stops differing:

- VN: 1944-06-20, 1967-07-07, 2072-12-09, 2077-11-15 (new moon at local midnight);
  2033-09-23..2033-11-22 (reference: leap month 8, method: leap month 11);
  2053-01-20 (reference: leap month 12 of 2052).
- CN: 2097-08-07 (new moon at local midnight); 2033-08-25..2033-11-22 (reference:
  leap month 7, method: leap month 11).

Hồ Ngọc Đức's published code (checked via the `cky-lunar-calendar` port) diverges
on exactly these rows, so the port is faithful. For CN 1929-2100, a cross-check
against `lunar-javascript` 1.7.8 (MIT), which follows the official Chinese calendar,
differs only on 2057-09-28 and 2097-08-07. On 2033 the method and the official
calendar agree (leap month 11). Before 1929 the official calendar used Beijing local
time, so it also differs on 1914-11-18, 1916-02-04 and 1920-11-11.

## Regenerate

Node only through Docker, from the repo root (Git Bash):

```
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/tools/fixtures/lunar:/gen:ro" node:22-alpine \
  sh -c "cd /tmp && npm init -y >/dev/null && npm i --no-audit --no-fund date-chinese@2.2.1 >/dev/null 2>&1 && cp /gen/generate.mjs . && node generate.mjs" \
  > services/chart-svc/tests/fixtures/lunar/vn-1900-2100.json
```

On Linux or macOS, use `$(pwd)` and drop `MSYS_NO_PATHCONV=1`. The script is
`tools/fixtures/lunar/generate.mjs` and takes about 6 s.
