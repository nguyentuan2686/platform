//! Birth-time normalisation: local civil time in an IANA zone to the solar date and
//! hour branch at the calendar's standard offset.

use crate::domain::calendar::{hour_branch, solar_to_lunar, Branch, CalendarKind, LunarDate};
use jiff::tz::AmbiguousOffset;
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

/// Supported solar years after normalisation.
const YEARS: std::ops::RangeInclusive<i32> = 1900..=2100;

/// Normalises a local birth time in IANA zone `zone` to the calendar's standard offset.
///
/// The zone's historical offset applies (South Vietnam was UTC+8 in 1959-1975). In a
/// daylight-saving overlap the earlier instant is taken. Hour 23 (giờ Tý) counts as the
/// next solar day.
pub fn normalize(
    local: PrimitiveDateTime,
    zone: &str,
    cal: CalendarKind,
) -> Result<NormalizedBirth, BirthError> {
    let tz = jiff::tz::TimeZone::get(zone).map_err(|_| BirthError::UnknownZone)?;
    let civil = to_civil(local)?;
    let ambiguous = tz.to_ambiguous_zoned(civil);
    if matches!(ambiguous.offset(), AmbiguousOffset::Gap { .. }) {
        return Err(BirthError::Nonexistent);
    }
    let instant = ambiguous
        .earlier()
        .map_err(|_| BirthError::OutOfRange)?
        .timestamp();
    let standard =
        jiff::tz::Offset::from_hours(cal.offset_hours()).map_err(|_| BirthError::OutOfRange)?;
    let wall = instant.to_zoned(jiff::tz::TimeZone::fixed(standard));
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
    fn south_vietnam_1970_was_utc_plus_8() {
        let b = normalize(at(1970, 6, 1, 23, 30), "Asia/Ho_Chi_Minh", Vn).unwrap();
        assert_eq!(b.solar_date, date(1970, 6, 1));
        assert_eq!(b.hour_branch, Branch::Hai);
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
    fn taipei_is_already_utc_plus_8() {
        let b = normalize(at(1994, 3, 12, 8, 30), "Asia/Taipei", Cn).unwrap();
        assert_eq!(b.solar_date, date(1994, 3, 12));
        assert_eq!(b.hour_branch, Branch::Chen);
    }

    #[test]
    fn new_york_noon_is_next_day_midnight_in_vietnam() {
        let b = normalize(at(2000, 1, 1, 12, 0), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2000, 1, 2));
        assert_eq!(b.hour_branch, Branch::Zi);
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
        // Earlier = 05:30 UTC = 12:30 in UTC+7.
        let b = normalize(at(2021, 11, 7, 1, 30), "America/New_York", Vn).unwrap();
        assert_eq!(b.solar_date, date(2021, 11, 7));
        assert_eq!(b.hour_branch, Branch::Wu);
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
    fn range_is_checked_after_normalisation() {
        // 2100-12-31 23:30 rolls to 2101-01-01.
        assert_eq!(
            normalize(at(2100, 12, 31, 23, 30), "Asia/Ho_Chi_Minh", Vn),
            Err(BirthError::OutOfRange)
        );
    }

    #[test]
    fn from_parts_computes_lunar_date() {
        let b = NormalizedBirth::from_parts(date(1994, 3, 12), Branch::Chen, Vn);
        assert_eq!(b.lunar, solar_to_lunar(date(1994, 3, 12), Vn));
    }
}
