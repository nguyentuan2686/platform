//! Birth-time normalisation: local civil time in an IANA zone to the solar date and
//! hour branch in the birthplace's local standard time.
//!
//! Hour convention (ruling R8): the hour branch and the 23:xx roll to the next day (giờ Tý)
//! use the birthplace's local standard time, i.e. the zone's offset at that instant with
//! daylight saving removed. `Asia/Ho_Chi_Minh` (alias `Asia/Saigon`) is always normalised
//! to UTC+7, which removes South Vietnam's UTC+8 period (1960-01-01..1975-06-12). The
//! lunar date is the resulting local date in the chosen calendar: the calendar changes
//! the lunar date only, never the hour.

use crate::domain::calendar::{hour_branch, solar_to_lunar, Branch, CalendarKind, LunarDate};
use jiff::tz::{AmbiguousOffset, Offset, TimeZone};
use jiff::Timestamp;
use time::{Date, Month, PrimitiveDateTime};

/// A birth reduced to what the chart needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NormalizedBirth {
    pub solar_date: Date,
    pub hour_branch: Branch,
    pub lunar: LunarDate,
    pub calendar: CalendarKind,
}

impl NormalizedBirth {
    /// Builds a birth from an already normalised solar date and hour branch.
    pub fn from_parts(solar_date: Date, hour_branch: Branch, calendar: CalendarKind) -> Self {
        Self {
            solar_date,
            hour_branch,
            lunar: solar_to_lunar(solar_date, calendar),
            calendar,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum BirthError {
    #[error("unknown time zone")]
    UnknownZone,
    #[error("birth outside the supported years 1900-2100")]
    OutOfRange,
    #[error("local time does not exist in the zone (daylight saving gap)")]
    Nonexistent,
}

/// Supported solar years of the resulting local date.
const YEARS: std::ops::RangeInclusive<i32> = 1900..=2100;

/// Zones always normalised to UTC+7.
const VIETNAM_ZONES: [&str; 2] = ["Asia/Ho_Chi_Minh", "Asia/Saigon"];

/// Normalises a local birth time in IANA zone `zone` to the birthplace's local standard
/// time (see the module docs).
///
/// The zone's historical offset gives the instant; a time in a gap is
/// [`BirthError::Nonexistent`] and in an overlap the earlier instant is taken. Hour 23
/// (giờ Tý) counts as the next solar day. The year range applies to the resulting date.
pub fn normalize(
    local: PrimitiveDateTime,
    zone: &str,
    cal: CalendarKind,
) -> Result<NormalizedBirth, BirthError> {
    let tz = TimeZone::get(zone).map_err(|_| BirthError::UnknownZone)?;
    let ambiguous = tz.to_ambiguous_zoned(to_civil(local)?);
    if matches!(ambiguous.offset(), AmbiguousOffset::Gap { .. }) {
        return Err(BirthError::Nonexistent);
    }
    let instant = ambiguous
        .earlier()
        .map_err(|_| BirthError::OutOfRange)?
        .timestamp();
    let standard = if VIETNAM_ZONES.iter().any(|z| z.eq_ignore_ascii_case(zone)) {
        Offset::constant(7)
    } else {
        standard_offset(&tz, instant)
    };
    let wall = instant.to_zoned(TimeZone::fixed(standard));
    let mut solar_date = from_civil(wall.date())?;
    let hour = u8::try_from(wall.hour()).map_err(|_| BirthError::OutOfRange)?;
    if hour == 23 {
        solar_date = solar_date.next_day().ok_or(BirthError::OutOfRange)?;
    }
    if !YEARS.contains(&solar_date.year()) {
        return Err(BirthError::OutOfRange);
    }
    Ok(NormalizedBirth::from_parts(
        solar_date,
        hour_branch(hour),
        cal,
    ))
}

/// The zone's standard offset at `at`: its offset there, minus the daylight-saving delta
/// when daylight saving is in effect. The standard offset is the nearest non-DST offset
/// before (else after) `at`; one hour is assumed if the zone has none.
fn standard_offset(tz: &TimeZone, at: Timestamp) -> Offset {
    let info = tz.to_offset_info(at);
    if !info.dst().is_dst() {
        return info.offset();
    }
    tz.preceding(at)
        .find(|t| !t.dst().is_dst())
        .or_else(|| tz.following(at).find(|t| !t.dst().is_dst()))
        .map_or_else(
            || Offset::from_seconds(info.offset().seconds() - 3600).unwrap_or(info.offset()),
            |t| t.offset(),
        )
}

fn to_civil(local: PrimitiveDateTime) -> Result<jiff::civil::DateTime, BirthError> {
    let year = i16::try_from(local.year()).map_err(|_| BirthError::OutOfRange)?;
    jiff::civil::DateTime::new(
        year,
        i8::try_from(u8::from(local.month())).map_err(|_| BirthError::OutOfRange)?,
        i8::try_from(local.day()).map_err(|_| BirthError::OutOfRange)?,
        i8::try_from(local.hour()).map_err(|_| BirthError::OutOfRange)?,
        i8::try_from(local.minute()).map_err(|_| BirthError::OutOfRange)?,
        i8::try_from(local.second()).map_err(|_| BirthError::OutOfRange)?,
        0,
    )
    .map_err(|_| BirthError::OutOfRange)
}

fn from_civil(d: jiff::civil::Date) -> Result<Date, BirthError> {
    let month = Month::try_from(u8::try_from(d.month()).map_err(|_| BirthError::OutOfRange)?)
        .map_err(|_| BirthError::OutOfRange)?;
    let day = u8::try_from(d.day()).map_err(|_| BirthError::OutOfRange)?;
    Date::from_calendar_date(i32::from(d.year()), month, day).map_err(|_| BirthError::OutOfRange)
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::Time;
    use CalendarKind::{Cn, Vn};

    fn at(y: i32, mo: u8, d: u8, h: u8, mi: u8) -> PrimitiveDateTime {
        PrimitiveDateTime::new(
            Date::from_calendar_date(y, Month::try_from(mo).unwrap(), d).unwrap(),
            Time::from_hms(h, mi, 0).unwrap(),
        )
    }

    fn date(y: i32, mo: u8, d: u8) -> Date {
        Date::from_calendar_date(y, Month::try_from(mo).unwrap(), d).unwrap()
    }
    #[test]
    fn south_vietnam_1970_is_normalised_to_utc_plus_7() {
        let b = normalize(at(1970, 6, 1, 23, 30), "Asia/Ho_Chi_Minh", Vn).unwrap();
        assert_eq!(b.solar_date, date(1970, 6, 1));
        assert_eq!(b.hour_branch, Branch::Hai);
    }

    #[test]
    fn saigon_alias_is_normalised_like_ho_chi_minh() {
        let b = normalize(at(1970, 6, 1, 23, 30), "Asia/Saigon", Cn).unwrap();
        assert_eq!(b.solar_date, date(1970, 6, 1));
        assert_eq!(b.hour_branch, Branch::Hai);
    }

    #[test]
    fn utc_plus_8_period_boundaries_in_south_vietnam() {
        // tzdata: +07 until 1959-12-31 23:00, then +08 until 1975-06-13 00:00 (+08), when
        // clocks went back to 1975-06-12 23:00 (+07). Every instant is read at UTC+7.
        let z = "Asia/Ho_Chi_Minh";
        let cases = [
            ((1959, 12, 31, 22, 30), (1959, 12, 31), Branch::Hai),
            // 00:30 at +08 is 23:30 on 12-31 at UTC+7: giờ Tý of the next day.
            ((1960, 1, 1, 0, 30), (1960, 1, 1), Branch::Zi),
            // 23:30 happens twice; the earlier (+08) is 22:30 at UTC+7.
            ((1975, 6, 12, 23, 30), (1975, 6, 12), Branch::Hai),
            ((1975, 6, 13, 23, 30), (1975, 6, 14), Branch::Zi),
        ];
        for ((y, mo, d, h, mi), (ey, em, ed), branch) in cases {
            let b = normalize(at(y, mo, d, h, mi), z, Vn).unwrap();
            assert_eq!(b.solar_date, date(ey, em, ed), "{y}-{mo}-{d}T{h}:{mi}");
            assert_eq!(b.hour_branch, branch, "{y}-{mo}-{d}T{h}:{mi}");
        }
        // 1959-12-31 23:00-24:00 was skipped.
        assert_eq!(
            normalize(at(1959, 12, 31, 23, 30), z, Vn),
            Err(BirthError::Nonexistent)
        );
    }

    #[test]
    fn hour_23_rolls_to_next_day() {
        let b = normalize(at(1994, 3, 12, 23, 10), "Asia/Ho_Chi_Minh", Vn).unwrap();
        assert_eq!(b.solar_date, date(1994, 3, 13));
        assert_eq!(b.hour_branch, Branch::Zi);
        assert_eq!(b.lunar, solar_to_lunar(date(1994, 3, 13), Vn));
    }

    #[test]
    fn morning_in_vietnam() {
        let b = normalize(at(1994, 3, 12, 8, 30), "Asia/Ho_Chi_Minh", Vn).unwrap();
        assert_eq!(b.solar_date, date(1994, 3, 12));
        assert_eq!(b.hour_branch, Branch::Chen);
        assert_eq!(
            b.lunar,
            LunarDate {
                year: 1994,
                month: 2,
                day: 1,
                leap: false
            }
        );
        assert_eq!(b.calendar, Vn);
    }

    #[test]
    fn calendar_changes_only_the_lunar_date_never_the_hour() {
        let vn = normalize(at(1994, 3, 12, 8, 30), "Asia/Ho_Chi_Minh", Vn).unwrap();
        let cn = normalize(at(1994, 3, 12, 8, 30), "Asia/Ho_Chi_Minh", Cn).unwrap();
        assert_eq!(cn.hour_branch, Branch::Chen);
        assert_eq!(cn.solar_date, vn.solar_date);
        assert_eq!(cn.lunar, solar_to_lunar(date(1994, 3, 12), Cn));
        assert_eq!(cn.calendar, Cn);
    }

    #[test]
    fn taipei_hour_is_local() {
        let b = normalize(at(1994, 3, 12, 8, 30), "Asia/Taipei", Cn).unwrap();
        assert_eq!(b.solar_date, date(1994, 3, 12));
        assert_eq!(b.hour_branch, Branch::Chen);
    }

    #[test]
    fn new_york_winter_noon_is_local_noon_in_both_calendars() {
        for cal in [Vn, Cn] {
            let b = normalize(at(2000, 1, 1, 12, 0), "America/New_York", cal).unwrap();
            assert_eq!(b.solar_date, date(2000, 1, 1), "{cal:?}");
            assert_eq!(b.hour_branch, Branch::Wu, "{cal:?}");
            assert_eq!(b.lunar, solar_to_lunar(date(2000, 1, 1), cal), "{cal:?}");
        }
    }

    #[test]
    fn daylight_saving_is_removed() {
        // 12:30 EDT is 11:30 EST: hour Ngọ (11-13).
        let b = normalize(at(2000, 7, 1, 12, 30), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2000, 7, 1));
        assert_eq!(b.hour_branch, Branch::Wu);
        // 00:30 EDT is 23:30 EST on the previous day: giờ Tý of the same civil day.
        let b = normalize(at(2000, 7, 2, 0, 30), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2000, 7, 2));
        assert_eq!(b.hour_branch, Branch::Zi);
        // 23:30 EDT is 22:30 EST: hour Hợi, no roll.
        let b = normalize(at(2000, 7, 1, 23, 30), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2000, 7, 1));
        assert_eq!(b.hour_branch, Branch::Hai);
    }

    #[test]
    fn dst_gap_is_nonexistent() {
        assert_eq!(
            normalize(at(2021, 3, 14, 2, 30), "America/New_York", Vn),
            Err(BirthError::Nonexistent)
        );
    }

    #[test]
    fn dst_overlap_takes_the_earlier_instant() {
        // 2021-11-07 01:30 happens twice in New York: EDT (UTC-4) then EST (UTC-5).
        // Earlier = 01:30 EDT = 00:30 EST: hour Tý, same civil day (the later one would
        // be 01:30 EST, hour Sửu).
        let b = normalize(at(2021, 11, 7, 1, 30), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2021, 11, 7));
        assert_eq!(b.hour_branch, Branch::Zi);
    }

    #[test]
    fn unknown_zone() {
        assert_eq!(
            normalize(at(1994, 3, 12, 8, 30), "Mars/Base", Vn),
            Err(BirthError::UnknownZone)
        );
    }

    #[test]
    fn out_of_range_years() {
        let z = "Asia/Ho_Chi_Minh";
        assert_eq!(
            normalize(at(1899, 12, 31, 12, 0), z, Vn),
            Err(BirthError::OutOfRange)
        );
        assert_eq!(
            normalize(at(2101, 1, 1, 12, 0), z, Vn),
            Err(BirthError::OutOfRange)
        );
        assert!(normalize(at(1900, 1, 1, 12, 0), z, Vn).is_ok());
        assert!(normalize(at(2100, 12, 31, 12, 0), z, Vn).is_ok());
    }

    #[test]
    fn range_is_checked_on_the_resulting_local_date() {
        // 2100-12-31 23:30 rolls to 2101-01-01.
        assert_eq!(
            normalize(at(2100, 12, 31, 23, 30), "Asia/Ho_Chi_Minh", Vn),
            Err(BirthError::OutOfRange)
        );
        // No daylight saving in New York in January 1900: 00:30 stays on 1900-01-01.
        assert!(normalize(at(1900, 1, 1, 0, 30), "America/New_York", Vn).is_ok());
    }

    #[test]
    fn from_parts_computes_lunar_date() {
        let b = NormalizedBirth::from_parts(date(1994, 3, 12), Branch::Chen, Vn);
        assert_eq!(b.lunar, solar_to_lunar(date(1994, 3, 12), Vn));
    }
}
