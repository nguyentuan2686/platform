# lasotuvi cross-check fixture

`generate.py` casts the 50 charts in `cases.json` with the open-source Vietnamese tử vi tool
**lasotuvi** and prints `services/chart-svc/tests/fixtures/lasotuvi.json`, which
`services/chart-svc/tests/vn_crosscheck.rs` compares chart-svc against.

## Reference

- Repository: <https://github.com/doanguyen/lasotuvi>, pinned to commit
  **`ace8379a3ea033674e8a9096f5b98a9e2041eace`** (2021-08-06, the head at the time of writing).
- Licence: **MIT**, Copyright (c) 2016 doanguyen. Only its output (placement facts) is stored
  here; none of its code is copied into the repo.
- Its code is untrusted. It runs only inside `python:3.12-alpine`, with this directory mounted
  read-only; nothing of it is installed or executed on the host. `--no-deps` is used because
  its pinned requirements (ephem, mypy, ...) are for development only.

## Regenerate

From this directory (Git Bash; on Linux or macOS use `$(pwd)` and drop `MSYS_NO_PATHCONV=1`):

```
SHA=ace8379a3ea033674e8a9096f5b98a9e2041eace
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/w:ro" -w /w python:3.12-alpine sh -c \
  "pip install --quiet --no-cache-dir --no-deps --disable-pip-version-check --root-user-action=ignore \
   https://github.com/doanguyen/lasotuvi/archive/$SHA.zip >&2 && python -I generate.py cases.json" \
  > ../../../services/chart-svc/tests/fixtures/lasotuvi.json
```

The output is deterministic (two runs are byte-identical).

## Cases

50 cases: 30 years spread over 1930-2025, 6 dates in VN-only leap months or Tết windows
where the Vietnamese and Chinese calendars differ (1985-01-21, 1968-01-29, 2007-02-17,
2023-04-01, 1985-04-05, 1987-08-25) and 14 pseudo-random dates (seed 20261124). Both genders;
all 12 hour branches appear. Birthplace `Asia/Ho_Chi_Minh`, calendar VN. Five cases fall in a
leap month (1979-08-05, 2009-07-14, 2023-04-01, 1985-04-05, 1987-08-25).

## Input normalisation (both tools see the same lunar birth moment)

- A case is a **solar date that is already the birthplace's local standard date** (23:xx
  already rolled to the next day, ruling R8) plus an **hour branch 0-11** (Tý = 0). No clock
  time and no time zone reaches lasotuvi, so its own giờ Tý / zone handling never applies.
  chart-svc is fed the same pair through `NormalizedBirth::from_parts`.
- lasotuvi converts the solar date to a lunar date at UTC+7 with Hồ Ngọc Đức's algorithm.
  The lunar dates equal chart-svc's VN calendar in all 50 cases (leap flag included).
- **Leap months.** lasotuvi ignores the leap flag: a birth in leap month m is charted as
  plain month m. chart-svc follows iztro's default (`fixLeap`): days 1-15 of leap month m are
  charted as month m, days 16-30 as month m + 1. For leap-month births on days 16-30 (two
  cases: 2009-07-14, 1985-04-05) `generate.py` therefore feeds lasotuvi the lunar date with
  month m + 1 (its lunar-input mode), and keeps what lasotuvi would have charted unnormalised
  under `leapRaw` (month, Mệnh, Thân). Without this the Mệnh and Thân palaces of those two
  cases differ by one palace. This is a school convention, listed in
  `docs/tuvi/vn-school-diff.md`; chart-svc is unchanged.
- lasotuvi keeps one module-level object per star and sets a brightness only where its table
  has a value; `generate.py` clears them before every case so a brightness never leaks from
  the previous case.

## What is stored per case

Lunar date, year can chi, cục, Mệnh and Thân branch, the branch of each of the 14 major
stars (must-match facts), the Tuần and Triệt pairs (lasotuvi marks two palaces for each),
and, where a lasotuvi star maps to a chart-svc key (mapping by lasotuvi `saoID` in
`generate.py`): the 14 minor stars, 40 adjective stars (29 shared and the 11 Vietnamese-only ones), the Tràng sinh, Bác sỹ and Thái tuế
rings, the four Tứ Hóa stars and the brightness of major and minor stars.
