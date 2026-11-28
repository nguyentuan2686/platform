# astro-chart-svc reference fixtures

Reference data for the golden tests of `services/astro-chart-svc` (accuracy gate: 1 arcminute).
Generated once and committed; the service never calls these sources at runtime.

**Swiss Ephemeris is used only here, in a throwaway container. It is never shipped:** no
Swiss Ephemeris code, data or crate appears in `services/`, `Cargo.lock` or any image
(check: `git grep -n -i swisseph -- services Cargo.lock` prints nothing).

## Files

| File | Source | Parameters | Licence basis |
|---|---|---|---|
| `services/astro-chart-svc/tests/fixtures/horizons-golden.json` | JPL Horizons API | `EPHEM_TYPE=OBSERVER CENTER=500@399 QUANTITIES=31 TIME_TYPE=UT ANG_FORMAT=DEG CSV_FORMAT=YES`, `TLIST_TYPE=JD`, `TLIST`=the epochs; `COMMAND` Sun 10, Moon 301, planets 199..899, Pluto 999, Chiron `2060;` | NASA/JPL data, public domain |
| `services/astro-chart-svc/data/pluto.csv`, `chiron.csv` | JPL Horizons API | same, `TIME_TYPE=TT`, `START_TIME='1899-12-21 00:00' STOP_TIME='2101-01-10 00:00' STEP_SIZE='5 d'` | NASA/JPL data, public domain; shipped in the image as the interpolation tables |
| `services/astro-chart-svc/tests/fixtures/swe-reference.json` | pyswisseph 2.10.3.2 (Swiss Ephemeris, Moshier mode, `FLG_MOSEPH`, tropical) | Placidus / whole-sign / equal cusps, Asc, MC, true and mean node | AGPL/commercial: reference values only, test fixture only, no code or data from it is redistributed |

## Contents

- `horizons-golden.json`: 65 epochs, apparent geocentric ecliptic lon/lat of date for the 11
  bodies (Sun, Moon, Mercury-Neptune, Pluto, Chiron). Epochs: 61 grid points (every 40 months
  from 1900-01-15 to 2100-11-15, hour `(k*7) % 24`), `1990-07-15T07:30Z` (snapshot birth),
  `2023-04-21T08:00Z` (Mercury station), `2100-12-31T12:00Z`, and the Moon one hour before a
  sign ingress. That ingress is picked deterministically: `horizons.mjs` queries the Moon
  hourly (`STEP_SIZE='60 m'`) from 2000-01-01 00:00 UT for 4 days and takes the last hourly
  sample still in its old sign, i.e. the first 30 degree crossing after 2000-01-01:
  `2000-01-02T21:00Z` (239.738 deg, crossing 240 within the next hour).
- `pluto.csv` / `chiron.csv`: header `# jde_start=<JDE of row 0> step_days=5 source=...`, then one
  `lon,lat` row (7 decimals) per 5 TT days: 14687 rows from 1899-12-21 TT (`jde_start=2415009.5`)
  to the last grid row <= 2101-01-10 (2101-01-06). Row 0 is two steps before 1900-01-01 and the
  last row is past 2100-12-31, so a 4-point Lagrange window always fits.
  Note: the plan text gave `jde_start=2415020.5`, which is 1900-01-01 and contradicts the stated
  start date 1899-12-21; the header holds the true JDE of row 0 (2415009.5).
- `swe-reference.json`: 41 cases: 40 deterministic (latitudes -45, -33.87, 0, 10.82, 21.03, 35.68,
  51.51, 60.17, 66.0 cycled, 8 longitudes cycled, dates 1900-2100) with Placidus, whole-sign and
  equal cusps, plus one at 69.65N where Placidus is undefined (`placidus: null`).

## Regeneration

Only Docker; no native Node or Python. Output is deterministic (fixed lists, no clock, no
randomness); a rerun reproduces identical files (Horizons data is stable for these epochs).

```
docker run --rm -v "$PWD:/w" -w /w node:22-alpine node tools/fixtures/astro/horizons.mjs
docker run --rm -v "$PWD:/w" -w /w python:3.13-slim sh -c "apt-get update -qq && apt-get install -y -qq gcc g++ libc6-dev && pip install -q -r tools/fixtures/astro/swe/requirements.txt && python -I tools/fixtures/astro/swe/gen.py"
```

`pyswisseph` has no wheel for Python 3.13, hence the compiler in the throwaway container.
`horizons.mjs` sends one request per body and one per table, at most one per second.

The `source` string of `swe-reference.json` carries `swe.__version__` (`20230604`, the Swiss
Ephemeris library release bundled in pyswisseph 2.10.3.2). On a slow machine set
`-e CFLAGS="-O0 -g0"` on the python `docker run` to compile the bundled sqlite quickly; it does
not change the numbers.
