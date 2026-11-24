// Generates services/chart-svc/tests/fixtures/iztro-golden.json from JS iztro (pinned in
// package-lock.json). Deterministic: seeded PRNG, no clock. See README.md.
import { astro } from 'iztro';

// ---- deterministic helpers ------------------------------------------------------------

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rng = mulberry32(20261124);
const randInt = (lo, hi) => lo + Math.floor(rng() * (hi - lo + 1));

const DAY = 86400000;
const toMs = (s) => Date.parse(`${s}T00:00:00Z`);
const iso = (ms) => new Date(ms).toISOString().slice(0, 10);
const addDays = (s, n) => iso(toMs(s) + n * DAY);
// iztro takes dates without leading zeros: 1994-3-12.
const plain = (s) => s.split('-').map(Number).join('-');

const MIN = toMs('1900-01-01');
const MAX = toMs('2100-12-31');

// Days on which chart-svc's CN calendar (Hồ Ngọc Đức method at UTC+8) and the official
// Chinese calendar that iztro uses start a lunar month on different days (see the lunar
// fixture README). A month-start shift changes every day of the affected month, so a
// window of 40 days either side is kept out of the CN equality cases.
const CN_CALENDAR_MISMATCH = ['1914-11-18', '1916-02-04', '1920-11-11', '2057-09-28', '2097-08-07'];
const nearMismatch = (s) => CN_CALENDAR_MISMATCH.some((m) => Math.abs(toMs(s) - toMs(m)) <= 40 * DAY);

// ---- extraction -------------------------------------------------------------------------

const star = (s) => [s.name, s.brightness || '', s.mutagen || ''];

function chartOf(a) {
  const l = a.rawDates.lunarDate;
  return {
    lunar: [l.lunarYear, l.lunarMonth, l.lunarDay, l.isLeap],
    year: a.rawDates.chineseDate.yearly,
    five: a.fiveElementsClass,
    soul: a.soul,
    body: a.body,
    bodyBranch: a.earthlyBranchOfBodyPalace,
    palaces: a.palaces.map((p) => ({
      name: p.name,
      stem: p.heavenlyStem,
      branch: p.earthlyBranch,
      body: p.isBodyPalace,
      major: p.majorStars.map(star),
      minor: p.minorStars.map(star),
      decade: p.decadal.range,
    })),
  };
}

const layer = (x) => ({ index: x.index, stem: x.heavenlyStem, branch: x.earthlyBranch, mutagens: x.mutagen });

function flowOf(a, date) {
  const h = a.horoscope(plain(date), 0);
  const l = astro.bySolar(plain(date), 0, '男', true, 'zh-CN').rawDates.lunarDate;
  return {
    date,
    lunar: [l.lunarYear, l.lunarMonth, l.lunarDay, l.isLeap],
    yearly: layer(h.yearly),
    monthly: layer(h.monthly),
    daily: layer(h.daily),
  };
}

const genderName = (g) => (g === 'male' ? '男' : '女');
const bySolar = (solar, ti, g) => astro.bySolar(plain(solar), ti, genderName(g), true, 'zh-CN');
const byLunar = ([y, m, d, leap], ti, g) => astro.byLunar(`${y}-${m}-${d}`, ti, genderName(g), leap, true, 'zh-CN');

function randomFlowDate(birth) {
  for (;;) {
    const ms = toMs(birth) + randInt(0, 80 * 365) * DAY;
    const d = iso(Math.min(ms, MAX));
    if (!nearMismatch(d)) return d;
  }
}

// ---- cases ------------------------------------------------------------------------------

const cases = [];

// CN path: chart by solar date, as iztro computes it. Time indices 0-11 only (ruling R3:
// 23:xx is next-day Zi, index 0; iztro's late-Zi index 12 never occurs).
function cn(id, solar, timeIndex, gender, flowDate) {
  if (nearMismatch(solar)) throw new Error(`${id}: ${solar} is near a CN calendar mismatch`);
  const a = bySolar(solar, timeIndex, gender);
  cases.push({ id, kind: 'cn', solar, timeIndex, gender, expect: { ...chartOf(a), flow: flowOf(a, flowDate) } });
}

// VN path: chart-svc feeds the engine its own VN lunar date, so the expectation is
// iztro's chart by lunar input. `lunar` is the VN lunar date the test re-derives from
// `solar` with chart-svc's calendar; `engineLunar` (if present) is the input given to
// iztro when the VN date cannot be fed verbatim (see README, "VN-only leap months").
function vn(id, solar, lunar, timeIndex, gender, flowDate, engineLunar = lunar) {
  const a = byLunar(engineLunar, timeIndex, gender);
  cases.push({ id, kind: 'vn', solar, lunar, timeIndex, gender, expect: { ...chartOf(a), flow: flowOf(a, flowDate) } });
}

// Late Zi (ruling R3): a 23:30 birth in `zone` equals the next day's index-0 chart.
function lateZi(id, local, zone, calendar, gender, expectFrom) {
  cases.push({ id, kind: 'lateZi', local, zone, calendar, gender, expect: chartOf(expectFrom) });
}

// 300 random CN cases spread over 1900-2100, both genders, time indices 0-11.
for (let i = 0; cases.length < 300; i++) {
  const solar = iso(MIN + randInt(0, (MAX - MIN) / DAY) * DAY);
  if (nearMismatch(solar)) continue;
  const gender = rng() < 0.5 ? 'male' : 'female';
  cn(`random-${String(cases.length).padStart(3, '0')}`, solar, cases.length % 12, gender, randomFlowDate(solar));
}

// Review Focus dates on the CN path.
const FLOW = '2026-11-25';
cn('happy-path-1994-03-12', '1994-03-12', 4, 'male', FLOW);
cn('vn-cn-split-1985-02-01-male', '1985-02-01', 4, 'male', FLOW);
cn('vn-cn-split-1985-02-01-female', '1985-02-01', 4, 'female', FLOW);
cn('tet-vn-1985-01-21', '1985-01-21', 0, 'male', FLOW);
cn('tet-cn-1985-02-20', '1985-02-20', 1, 'female', FLOW);
cn('tet-vn-1968-01-29', '1968-01-29', 2, 'male', FLOW);
cn('tet-cn-1968-01-30', '1968-01-30', 3, 'female', FLOW);
cn('tet-vn-2007-02-17', '2007-02-17', 5, 'male', FLOW);
cn('tet-cn-2007-02-18', '2007-02-18', 6, 'female', FLOW);
cn('leap-2023-04-01', '2023-04-01', 6, 'male', FLOW);
cn('leap-2023-04-01-second-half', '2023-04-10', 6, 'female', FLOW);
cn('south-vn-1970-06-01-hai', '1970-06-01', 11, 'male', FLOW);
cn('flow-in-leap-month', '1990-05-05', 7, 'female', '2023-04-15');

// VN path (Review Focus 3 and 5): VN lunar dates as computed by chart-svc.
vn('vn-1985-02-01-male', '1985-02-01', [1985, 1, 12, false], 4, 'male', FLOW);
vn('vn-1985-02-01-female', '1985-02-01', [1985, 1, 12, false], 4, 'female', FLOW);
vn('vn-tet-1985-01-21', '1985-01-21', [1985, 1, 1, false], 0, 'male', FLOW);
vn('vn-tet-1968-01-29', '1968-01-29', [1968, 1, 1, false], 2, 'male', FLOW);
vn('vn-tet-2007-02-17', '2007-02-17', [2007, 1, 1, false], 5, 'male', FLOW);
vn('vn-leap-2023-04-01', '2023-04-01', [2023, 2, 11, true], 6, 'male', FLOW);
vn('vn-leap-2023-04-10', '2023-04-10', [2023, 2, 20, true], 6, 'female', FLOW);
vn('vn-happy-path-1994-03-12', '1994-03-12', [1994, 2, 1, false], 4, 'male', FLOW);
vn('vn-south-1970-06-01-hai', '1970-06-01', [1970, 4, 28, false], 11, 'male', FLOW);
// 1985 has a leap 2nd month in VN only. First half: the leap month charts as month 2;
// second half (day > 15, iztro fixLeap) as month 3. iztro would ignore the leap flag.
vn('vn-only-leap-1985-first-half', '1985-03-30', [1985, 2, 10, true], 3, 'male', FLOW, [1985, 2, 10, false]);
vn('vn-only-leap-1985-second-half', '1985-04-10', [1985, 2, 21, true], 3, 'female', FLOW, [1985, 3, 21, false]);

// Late Zi (Review Focus 1), including month and lunar-year boundaries.
lateZi('late-zi-cn-1994-03-12', '1994-03-12T23:30', 'Asia/Shanghai', 'CN', 'male', bySolar('1994-03-13', 0, 'male'));
lateZi('late-zi-cn-month-end-2023-03-21', '2023-03-21T23:30', 'Asia/Shanghai', 'CN', 'female', bySolar('2023-03-22', 0, 'female'));
lateZi('late-zi-cn-tet-eve-2007-02-17', '2007-02-17T23:30', 'Asia/Shanghai', 'CN', 'male', bySolar('2007-02-18', 0, 'male'));
lateZi('late-zi-cn-year-end-1999-12-31', '1999-12-31T23:30', 'Asia/Shanghai', 'CN', 'female', bySolar('2000-01-01', 0, 'female'));
lateZi('late-zi-vn-tet-eve-1985-01-20', '1985-01-20T23:30', 'Asia/Ho_Chi_Minh', 'VN', 'male', byLunar([1985, 1, 1, false], 0, 'male'));

// ---- self-check for the VN-only leap month mapping --------------------------------------
// For every real (CN) leap month 1900-2100, a leap-month birth charts like the same day
// in month m (day <= 15) or month m + 1 (day > 15). This justifies feeding VN-only leap
// months to the engine as plain months.
let leapChecks = 0;
for (let y = 1900; y <= 2099; y++) {
  for (let m = 1; m <= 11; m++) {
    let leapChart;
    try {
      leapChart = byLunar([y, m, 1, true], 0, 'male');
    } catch {
      continue;
    }
    if (!leapChart.rawDates.lunarDate.isLeap) continue;
    for (const d of [10, 20]) {
      const palaces = (a) => JSON.stringify(chartOf(a).palaces) + chartOf(a).five + chartOf(a).soul + chartOf(a).body;
      const leap = byLunar([y, m, d, true], 5, 'female');
      const plainMonth = byLunar([y, d > 15 ? m + 1 : m, d, false], 5, 'female');
      if (palaces(leap) !== palaces(plainMonth)) throw new Error(`leap equivalence fails for ${y}-${m}-${d}`);
      leapChecks += 1;
    }
  }
}

// One case per line keeps diffs of a regenerated fixture readable.
const SOURCE = 'iztro@2.6.3 (MIT), zh-CN names, default config, fixLeap=true; tools/fixtures/iztro/generate.mjs';
process.stdout.write(`{\n"source": ${JSON.stringify(SOURCE)},\n"leapEquivalenceChecks": ${leapChecks},\n"cases": [\n`);
process.stdout.write(cases.map((c) => JSON.stringify(c)).join(',\n'));
process.stdout.write('\n]\n}\n');
