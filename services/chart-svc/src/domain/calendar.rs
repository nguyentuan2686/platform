//! Lunisolar calendar and can chi (sexagenary cycle).
//!
//! Hồ Ngọc Đức's astronomical method: new moons and the sun's longitude from the
//! truncated series in Meeus, *Astronomical Algorithms*, evaluated at the calendar's
//! standard meridian (Vietnam UTC+7, China UTC+8). A lunar month starts on the local
//! day of a new moon; month 11 contains the winter solstice; in a 13-month year the
//! first month without a major solar term is the leap month. No lookup tables.

use std::f64::consts::PI;
use time::Date;

/// Which national lunar calendar to use; they differ only in the meridian.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CalendarKind {
    Vn,
    Cn,
}

impl CalendarKind {
    /// Standard offset from UTC in hours at which the calendar is computed.
    pub fn offset_hours(self) -> i8 {
        match self {
            Self::Vn => 7,
            Self::Cn => 8,
        }
    }

    /// The offset as a fraction of a day.
    fn offset_days(self) -> f64 {
        f64::from(self.offset_hours()) / 24.0
    }
}

/// A lunar date. `year` is the Gregorian year in which the lunar year starts; `leap`
/// marks the intercalary month that repeats `month`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct LunarDate {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    pub leap: bool,
}

/// Mean synodic month in days.
const SYNODIC_MONTH: f64 = 29.530_588_853;
/// Julian date of the new moon numbered k = 0 (1900-01-01).
const NEW_MOON_EPOCH: f64 = 2_415_021.076_998_695;

/// `floor` to an integer day or lunation count.
#[allow(clippy::cast_possible_truncation)] // callers pass values far inside i32 (|x| < 1e8)
fn floor(x: f64) -> i32 {
    x.floor() as i32
}

/// Julian day number of a proleptic Gregorian date (same numbering as `time`).
fn julian_day(year: i32, month: i32, day: i32) -> i32 {
    let a = (14 - month) / 12;
    let y = year + 4800 - a;
    let m = month + 12 * a - 3;
    day + (153 * m + 2) / 5 + 365 * y + y.div_euclid(4) - y.div_euclid(100) + y.div_euclid(400)
        - 32045
}

/// Julian date of the k-th new moon after 1900-01-01 (Meeus ch. 49, truncated series).
fn new_moon(k: i32) -> f64 {
    let k = f64::from(k);
    let t = k / 1236.85;
    let t2 = t * t;
    let t3 = t2 * t;
    let dr = PI / 180.0;
    let mut jd1 = 2_415_020.759_33 + 29.530_588_68 * k + 0.000_117_8 * t2 - 0.000_000_155 * t3;
    jd1 += 0.000_33 * ((166.56 + 132.87 * t - 0.009_173 * t2) * dr).sin();
    let m = (359.2242 + 29.105_356_08 * k - 0.000_033_3 * t2 - 0.000_003_47 * t3) * dr;
    let mpr = (306.0253 + 385.816_918_06 * k + 0.010_730_6 * t2 + 0.000_012_36 * t3) * dr;
    let f = (21.2964 + 390.670_506_46 * k - 0.001_652_8 * t2 - 0.000_002_39 * t3) * dr;
    let c1 = (0.1734 - 0.000_393 * t) * m.sin() + 0.0021 * (2.0 * m).sin() - 0.4068 * mpr.sin()
        + 0.0161 * (2.0 * mpr).sin()
        - 0.0004 * (3.0 * mpr).sin()
        + 0.0104 * (2.0 * f).sin()
        - 0.0051 * (m + mpr).sin()
        - 0.0074 * (m - mpr).sin()
        + 0.0004 * (2.0 * f + m).sin()
        - 0.0004 * (2.0 * f - m).sin()
        - 0.0006 * (2.0 * f + mpr).sin()
        + 0.0010 * (2.0 * f - mpr).sin()
        + 0.0005 * (2.0 * mpr + m).sin();
    let delta_t = if t < -11.0 {
        0.001 + 0.000_839 * t + 0.000_226_1 * t2 - 0.000_008_45 * t3 - 0.000_000_081 * t * t3
    } else {
        -0.000_278 + 0.000_265 * t + 0.000_262 * t2
    };
    jd1 + c1 - delta_t
}

/// Apparent longitude of the sun in radians, `[0, 2π)`, at Julian date `jd` (Meeus ch. 25).
fn sun_longitude(jd: f64) -> f64 {
    let t = (jd - 2_451_545.0) / 36525.0;
    let t2 = t * t;
    let dr = PI / 180.0;
    let m = (357.529_10 + 35_999.050_30 * t - 0.000_155_9 * t2 - 0.000_000_48 * t * t2) * dr;
    let l0 = 280.466_45 + 36_000.769_83 * t + 0.000_303_2 * t2;
    let dl = (1.914_600 - 0.004_817 * t - 0.000_014 * t2) * m.sin()
        + (0.019_993 - 0.000_101 * t) * (2.0 * m).sin()
        + 0.000_290 * (3.0 * m).sin();
    ((l0 + dl) * dr).rem_euclid(2.0 * PI)
}

/// Local day number on which the k-th new moon falls.
fn new_moon_day(k: i32, offset: f64) -> i32 {
    floor(new_moon(k) + 0.5 + offset)
}

/// The 30° sector (0..=11, 0 = vernal equinox) the sun is in at the start of local day `day`.
fn sun_sector(day: i32, offset: f64) -> i32 {
    floor(sun_longitude(f64::from(day) - 0.5 - offset) / PI * 6.0)
}

/// Day number on which lunar month 11 (the month containing the winter solstice)
/// of Gregorian year `year` starts.
fn month_11_start(year: i32, offset: f64) -> i32 {
    let off = julian_day(year, 12, 31) - 2_415_021;
    let k = floor(f64::from(off) / SYNODIC_MONTH);
    let nm = new_moon_day(k, offset);
    if sun_sector(nm, offset) >= 9 {
        new_moon_day(k - 1, offset)
    } else {
        nm
    }
}

/// For a 13-month year whose month 11 starts on day `a11`: how many months after it
/// the leap month (the first month without a major solar term) comes.
fn leap_month_offset(a11: i32, offset: f64) -> i32 {
    let k = floor((f64::from(a11) - NEW_MOON_EPOCH) / SYNODIC_MONTH + 0.5);
    let mut i = 1;
    let mut arc = sun_sector(new_moon_day(k + i, offset), offset);
    loop {
        let last = arc;
        i += 1;
        arc = sun_sector(new_moon_day(k + i, offset), offset);
        if arc == last || i >= 14 {
            return i - 1;
        }
    }
}

/// Narrows a lunar month (1..=12) or day (1..=30) number.
fn small(v: i32) -> u8 {
    // Infallible: only called with lunar month and day numbers, which lie in 1..=30.
    u8::try_from(v).expect("lunar month/day fits in u8")
}

/// Converts a solar (Gregorian) date, local to the calendar's meridian, to a lunar date.
pub fn solar_to_lunar(date: Date, cal: CalendarKind) -> LunarDate {
    let offset = cal.offset_days();
    let day_number = date.to_julian_day();
    let year = date.year();
    // The month starts on the latest new-moon day not after `day_number`. When the true
    // new moon runs late, both candidates k+1 and k of the mean-lunation estimate can
    // fall after the day, so step back until one does not (Hồ Ngọc Đức's code steps
    // back once and returns day 0, e.g. on 2054-05-07).
    let mut k = floor((f64::from(day_number) - NEW_MOON_EPOCH) / SYNODIC_MONTH) + 1;
    let mut month_start = new_moon_day(k, offset);
    while month_start > day_number {
        k -= 1;
        month_start = new_moon_day(k, offset);
    }
    let mut a11 = month_11_start(year, offset);
    let mut b11 = a11;
    let mut lunar_year;
    if a11 >= month_start {
        lunar_year = year;
        a11 = month_11_start(year - 1, offset);
    } else {
        lunar_year = year + 1;
        b11 = month_11_start(year + 1, offset);
    }
    let day = day_number - month_start + 1;
    let diff = (month_start - a11) / 29;
    let mut leap = false;
    let mut month = diff + 11;
    if b11 - a11 > 365 {
        let leap_diff = leap_month_offset(a11, offset);
        if diff >= leap_diff {
            month = diff + 10;
            leap = diff == leap_diff;
        }
    }
    if month > 12 {
        month -= 12;
    }
    if month >= 11 && diff < 4 {
        lunar_year -= 1;
    }
    LunarDate {
        year: lunar_year,
        month: small(month),
        day: small(day),
        leap,
    }
}

/// Converts a lunar date to its solar date; `None` if the lunar date does not exist
/// (month or day out of range, day 30 of a 29-day month, a leap month the year lacks).
pub fn lunar_to_solar(l: LunarDate, cal: CalendarKind) -> Option<Date> {
    let years = (Date::MIN.year() + 1)..Date::MAX.year();
    if !(1..=12).contains(&l.month) || !(1..=30).contains(&l.day) || !years.contains(&l.year) {
        return None;
    }
    let offset = cal.offset_days();
    let month = i32::from(l.month);
    let (a11, b11) = if month < 11 {
        (
            month_11_start(l.year - 1, offset),
            month_11_start(l.year, offset),
        )
    } else {
        (
            month_11_start(l.year, offset),
            month_11_start(l.year + 1, offset),
        )
    };
    let k = floor(0.5 + (f64::from(a11) - NEW_MOON_EPOCH) / SYNODIC_MONTH);
    let mut off = (month - 11).rem_euclid(12);
    if b11 - a11 > 365 {
        let leap_off = leap_month_offset(a11, offset);
        let leap_month = (leap_off - 2).rem_euclid(12);
        if l.leap && month != leap_month {
            return None;
        }
        if l.leap || off >= leap_off {
            off += 1;
        }
    } else if l.leap {
        return None;
    }
    let start = new_moon_day(k + off, offset);
    let date = Date::from_julian_day(start + i32::from(l.day) - 1).ok()?;
    // Rejects day 30 of a 29-day month (that solar day is day 1 of the next month).
    (solar_to_lunar(date, cal) == l).then_some(date)
}

/// Heavenly stem (can). The variant names are the stable ASCII keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Stem {
    Jia,
    Yi,
    Bing,
    Ding,
    Wu,
    Ji,
    Geng,
    Xin,
    Ren,
    Gui,
}

/// Earthly branch (chi). The variant names are the stable ASCII keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Branch {
    Zi,
    Chou,
    Yin,
    Mao,
    Chen,
    Si,
    Wu,
    Wei,
    Shen,
    You,
    Xu,
    Hai,
}

/// Position of `n` in a cycle of length `len`, wrapping negatives.
fn cycle_index(n: i64, len: i64) -> usize {
    // rem_euclid lies in 0..len, so the conversion cannot fail.
    usize::try_from(n.rem_euclid(len)).unwrap_or_default()
}

impl Stem {
    /// All stems in cycle order, starting at Giáp.
    pub const ALL: [Stem; 10] = [
        Stem::Jia,
        Stem::Yi,
        Stem::Bing,
        Stem::Ding,
        Stem::Wu,
        Stem::Ji,
        Stem::Geng,
        Stem::Xin,
        Stem::Ren,
        Stem::Gui,
    ];

    /// The `n`-th stem of the cycle (mod 10, Giáp = 0).
    fn nth(n: i64) -> Self {
        Self::ALL[cycle_index(n, 10)]
    }

    /// Stable ASCII key.
    pub fn key(self) -> &'static str {
        self.labels().0
    }

    /// Vietnamese label.
    pub fn name_vi(self) -> &'static str {
        self.labels().1
    }

    fn labels(self) -> (&'static str, &'static str) {
        match self {
            Stem::Jia => ("Jia", "Giáp"),
            Stem::Yi => ("Yi", "Ất"),
            Stem::Bing => ("Bing", "Bính"),
            Stem::Ding => ("Ding", "Đinh"),
            Stem::Wu => ("Wu", "Mậu"),
            Stem::Ji => ("Ji", "Kỷ"),
            Stem::Geng => ("Geng", "Canh"),
            Stem::Xin => ("Xin", "Tân"),
            Stem::Ren => ("Ren", "Nhâm"),
            Stem::Gui => ("Gui", "Quý"),
        }
    }
}

impl Branch {
    /// All branches in cycle order, starting at Tý.
    pub const ALL: [Branch; 12] = [
        Branch::Zi,
        Branch::Chou,
        Branch::Yin,
        Branch::Mao,
        Branch::Chen,
        Branch::Si,
        Branch::Wu,
        Branch::Wei,
        Branch::Shen,
        Branch::You,
        Branch::Xu,
        Branch::Hai,
    ];

    /// The `n`-th branch of the cycle (mod 12, Tý = 0).
    fn nth(n: i64) -> Self {
        Self::ALL[cycle_index(n, 12)]
    }

    /// Stable ASCII key.
    pub fn key(self) -> &'static str {
        self.labels().0
    }

    /// Vietnamese label.
    pub fn name_vi(self) -> &'static str {
        self.labels().1
    }

    fn labels(self) -> (&'static str, &'static str) {
        match self {
            Branch::Zi => ("Zi", "Tý"),
            Branch::Chou => ("Chou", "Sửu"),
            Branch::Yin => ("Yin", "Dần"),
            Branch::Mao => ("Mao", "Mão"),
            Branch::Chen => ("Chen", "Thìn"),
            Branch::Si => ("Si", "Tỵ"),
            Branch::Wu => ("Wu", "Ngọ"),
            Branch::Wei => ("Wei", "Mùi"),
            Branch::Shen => ("Shen", "Thân"),
            Branch::You => ("You", "Dậu"),
            Branch::Xu => ("Xu", "Tuất"),
            Branch::Hai => ("Hai", "Hợi"),
        }
    }
}

/// A stem-branch pair of the sexagenary cycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct CanChi {
    pub stem: Stem,
    pub branch: Branch,
}

/// Can chi of a lunar year (year 4 CE was Giáp Tý).
pub fn year_can_chi(lunar_year: i32) -> CanChi {
    CanChi {
        stem: Stem::nth(i64::from(lunar_year) - 4),
        branch: Branch::nth(i64::from(lunar_year) - 4),
    }
}

/// Can chi of a lunar month: month 1 is Dần and its stem follows from the year stem
/// (Giáp/Kỷ years start at Bính Dần). A leap month takes the can chi of the regular
/// month it repeats.
pub fn month_can_chi(l: LunarDate) -> CanChi {
    let month = i64::from(l.month);
    CanChi {
        stem: Stem::nth(i64::from(l.year) * 12 + month + 3),
        branch: Branch::nth(month + 1),
    }
}

/// Can chi of a solar day (2000-01-01 was Mậu Ngọ).
pub fn day_can_chi(date: Date) -> CanChi {
    let jd = i64::from(date.to_julian_day());
    CanChi {
        stem: Stem::nth(jd + 9),
        branch: Branch::nth(jd + 1),
    }
}

/// Branch of a clock hour (0..=23): 23 and 0 are Tý, 1-2 Sửu, ..., 21-22 Hợi.
/// Hours of 24 or more wrap modulo 24.
pub fn hour_branch(hour: u8) -> Branch {
    Branch::nth((i64::from(hour % 24) + 1) / 2)
}

#[cfg(test)]
mod tests {
    use super::CalendarKind::{Cn, Vn};
    use super::*;
    use time::Month;

    fn d(y: i32, m: u8, day: u8) -> Date {
        Date::from_calendar_date(y, Month::try_from(m).unwrap(), day).unwrap()
    }

    fn l(year: i32, month: u8, day: u8, leap: bool) -> LunarDate {
        LunarDate {
            year,
            month,
            day,
            leap,
        }
    }

    fn cc(stem: Stem, branch: Branch) -> CanChi {
        CanChi { stem, branch }
    }

    #[test]
    fn offsets() {
        assert_eq!(Vn.offset_hours(), 7);
        assert_eq!(Cn.offset_hours(), 8);
    }

    #[test]
    fn tet_1985_differs_between_calendars() {
        assert_eq!(solar_to_lunar(d(1985, 1, 21), Vn), l(1985, 1, 1, false));
        assert_eq!(solar_to_lunar(d(1985, 1, 21), Cn), l(1984, 12, 1, false));
        assert_eq!(solar_to_lunar(d(1985, 2, 20), Cn), l(1985, 1, 1, false));
    }

    #[test]
    fn tet_1968_differs_between_calendars() {
        assert_eq!(solar_to_lunar(d(1968, 1, 29), Vn), l(1968, 1, 1, false));
        assert_eq!(solar_to_lunar(d(1968, 1, 30), Cn), l(1968, 1, 1, false));
    }

    #[test]
    fn tet_2007_differs_between_calendars() {
        assert_eq!(solar_to_lunar(d(2007, 2, 17), Vn), l(2007, 1, 1, false));
        assert_eq!(solar_to_lunar(d(2007, 2, 18), Cn), l(2007, 1, 1, false));
    }

    #[test]
    fn tet_shared_by_both_calendars() {
        for (y, m, day) in [(2023, 1, 22), (2024, 2, 10), (2025, 1, 29), (2026, 2, 17)] {
            for cal in [Vn, Cn] {
                assert_eq!(
                    solar_to_lunar(d(y, m, day), cal),
                    l(y, 1, 1, false),
                    "{y}-{m}-{day} {cal:?}"
                );
            }
        }
    }

    #[test]
    fn leap_second_month_2023() {
        assert_eq!(solar_to_lunar(d(2023, 3, 22), Vn), l(2023, 2, 1, true));
        assert_eq!(solar_to_lunar(d(2023, 4, 1), Vn), l(2023, 2, 11, true));
        assert_eq!(solar_to_lunar(d(2023, 2, 20), Vn), l(2023, 2, 1, false));
        assert_eq!(
            lunar_to_solar(l(2023, 2, 11, true), Vn),
            Some(d(2023, 4, 1))
        );
    }

    #[test]
    fn late_new_moon_does_not_yield_day_zero() {
        // Both new-moon candidates the mean-lunation estimate offers fall after
        // this day; the published algorithm code returns day 0 for 2054-05-07.
        assert_eq!(solar_to_lunar(d(2054, 5, 6), Vn), l(2054, 3, 29, false));
        assert_eq!(solar_to_lunar(d(2054, 5, 7), Vn), l(2054, 3, 30, false));
    }

    #[test]
    fn invalid_lunar_dates_have_no_solar_date() {
        // 2023 has no leap 3rd month; 2024 has no leap month at all.
        assert_eq!(lunar_to_solar(l(2023, 3, 1, true), Vn), None);
        assert_eq!(lunar_to_solar(l(2024, 5, 1, true), Vn), None);
        // Lunar 2023/1 (Vn) has 29 days, so day 30 does not exist.
        assert_eq!(lunar_to_solar(l(2023, 1, 30, false), Vn), None);
        assert_eq!(lunar_to_solar(l(2023, 13, 1, false), Vn), None);
        assert_eq!(lunar_to_solar(l(2023, 0, 1, false), Vn), None);
        assert_eq!(lunar_to_solar(l(2023, 1, 0, false), Vn), None);
        assert_eq!(lunar_to_solar(l(i32::MAX, 1, 1, false), Vn), None);
    }

    /// Full daily sweep, both calendars (about 2 s in a debug build).
    #[test]
    fn round_trip_every_day_1900_2100() {
        let mut date = d(1900, 1, 31);
        let end = d(2100, 12, 31);
        while date <= end {
            for cal in [Vn, Cn] {
                let lunar = solar_to_lunar(date, cal);
                assert!((1..=30).contains(&lunar.day), "{date} {cal:?}: {lunar:?}");
                assert_eq!(lunar_to_solar(lunar, cal), Some(date), "{date} {cal:?}");
            }
            date = date.next_day().unwrap();
        }
    }

    #[test]
    fn year_can_chi_values() {
        assert_eq!(year_can_chi(1994), cc(Stem::Jia, Branch::Xu));
        assert_eq!(year_can_chi(2026), cc(Stem::Bing, Branch::Wu));
        assert_eq!(year_can_chi(1984), cc(Stem::Jia, Branch::Zi));
        assert_eq!(year_can_chi(4), cc(Stem::Jia, Branch::Zi));
        assert_eq!(year_can_chi(3), cc(Stem::Gui, Branch::Hai));
    }

    #[test]
    fn month_can_chi_follows_year_stem() {
        // Giap year: month 1 is Binh Dan, month 12 is Dinh Suu.
        assert_eq!(
            month_can_chi(l(1984, 1, 1, false)),
            cc(Stem::Bing, Branch::Yin)
        );
        assert_eq!(
            month_can_chi(l(1984, 12, 1, false)),
            cc(Stem::Ding, Branch::Chou)
        );
        // At year: month 1 is Mau Dan; month 11 is Mau Ty.
        assert_eq!(
            month_can_chi(l(1985, 1, 1, false)),
            cc(Stem::Wu, Branch::Yin)
        );
        assert_eq!(
            month_can_chi(l(1985, 11, 1, false)),
            cc(Stem::Wu, Branch::Zi)
        );
        // Quy Mao 2023: month 2 is At Mao; the leap 2nd month shares it.
        assert_eq!(
            month_can_chi(l(2023, 2, 1, false)),
            cc(Stem::Yi, Branch::Mao)
        );
        assert_eq!(
            month_can_chi(l(2023, 2, 11, true)),
            cc(Stem::Yi, Branch::Mao)
        );
    }

    #[test]
    fn day_can_chi_values() {
        assert_eq!(day_can_chi(d(2000, 1, 1)), cc(Stem::Wu, Branch::Wu));
        assert_eq!(day_can_chi(d(2000, 1, 2)), cc(Stem::Ji, Branch::Wei));
        assert_eq!(day_can_chi(d(1999, 12, 31)), cc(Stem::Ding, Branch::Si));
    }

    #[test]
    fn hour_branches() {
        assert_eq!(hour_branch(23), Branch::Zi);
        assert_eq!(hour_branch(0), Branch::Zi);
        assert_eq!(hour_branch(1), Branch::Chou);
        assert_eq!(hour_branch(2), Branch::Chou);
        assert_eq!(hour_branch(8), Branch::Chen);
        assert_eq!(hour_branch(11), Branch::Wu);
        assert_eq!(hour_branch(22), Branch::Hai);
    }

    #[test]
    fn vietnamese_labels() {
        let stems: Vec<_> = Stem::ALL.iter().map(|s| s.name_vi()).collect();
        assert_eq!(
            stems,
            ["Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"]
        );
        let branches: Vec<_> = Branch::ALL.iter().map(|b| b.name_vi()).collect();
        assert_eq!(
            branches,
            [
                "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"
            ]
        );
        assert_eq!(Stem::Jia.key(), "Jia");
        assert_eq!(Branch::Hai.key(), "Hai");
    }
}
