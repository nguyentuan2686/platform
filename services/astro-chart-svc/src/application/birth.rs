//! Birth normalisation: local civil time in an IANA zone to the true unix instant.
//!
//! Unlike the tử vi service there is no standard-time or hour-branch rule: astrology needs
//! the real instant, so the zone's historical offset (jiff tz database) is used as is.

use jiff::tz::{AmbiguousOffset, TimeZone};
use time::{Date, PrimitiveDateTime, Time};

/// A birth as received: local wall-clock time, IANA zone and place.
#[derive(Debug, Clone, PartialEq)]
pub struct Birth {
    pub local: PrimitiveDateTime,
    pub zone: String,
    pub lat: f64,
    pub lon: f64,
    pub time_known: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum BirthError {
    #[error("unknown time zone")]
    UnknownZone,
    #[error("local time does not exist in the zone (DST gap)")]
    Nonexistent,
    #[error("birth outside the supported years 1900-2100")]
    OutOfRange,
    #[error("latitude must be within [-90, 90]")]
    BadLatitude,
    #[error("longitude must be within [-180, 180]")]
    BadLongitude,
}

pub(crate) const NOON: Time = match Time::from_hms(12, 0, 0) {
    Ok(t) => t,
    Err(_) => Time::MIDNIGHT,
};

/// Supported years of the local date.
const YEARS: std::ops::RangeInclusive<i32> = 1900..=2100;

/// Unix seconds of the birth. With `time_known == false` the instant is 12:00 local of the
/// local date. A time in a DST gap is [`BirthError::Nonexistent`]; in an overlap the
/// earlier instant is taken.
pub fn birth_instant(b: &Birth) -> Result<i64, BirthError> {
    if !(-90.0..=90.0).contains(&b.lat) {
        return Err(BirthError::BadLatitude);
    }
    if !(-180.0..=180.0).contains(&b.lon) {
        return Err(BirthError::BadLongitude);
    }
    let tz = TimeZone::get(&b.zone).map_err(|_| BirthError::UnknownZone)?;
    let local = if b.time_known {
        b.local
    } else {
        PrimitiveDateTime::new(b.local.date(), NOON)
    };
    check_year(local.date())?;
    let ambiguous = tz.to_ambiguous_zoned_datetime(to_civil(local)?);
    if matches!(ambiguous.offset(), AmbiguousOffset::Gap { .. }) {
        return Err(BirthError::Nonexistent);
    }
    let zoned = ambiguous.earlier().map_err(|_| BirthError::OutOfRange)?;
    Ok(zoned.timestamp().as_second())
}

/// Unix seconds of 00:00:00 and 23:59:59 local on `date` in `zone`.
///
/// A boundary that falls in a DST gap uses jiff's compatible disambiguation, i.e. the
/// first valid instant after the gap; in an overlap, the earlier instant.
pub fn local_day_bounds(date: Date, zone: &str) -> Result<(i64, i64), BirthError> {
    let tz = TimeZone::get(zone).map_err(|_| BirthError::UnknownZone)?;
    check_year(date)?;
    let last = Time::from_hms(23, 59, 59).map_err(|_| BirthError::OutOfRange)?;
    Ok((
        local_edge(&tz, date, Time::MIDNIGHT)?,
        local_edge(&tz, date, last)?,
    ))
}

/// Unix seconds of 00:00:00 local on `date` and of 00:00:00 local on the next day: the day
/// as the half-open interval [start, next start). Only `date` must be in 1900-2100 (the
/// next day of 2100-12-31 is 2101-01-01). Gaps and overlaps as in [`local_day_bounds`].
pub fn local_day_span(date: Date, zone: &str) -> Result<(i64, i64), BirthError> {
    let tz = TimeZone::get(zone).map_err(|_| BirthError::UnknownZone)?;
    check_year(date)?;
    let next = date.next_day().ok_or(BirthError::OutOfRange)?;
    Ok((
        local_edge(&tz, date, Time::MIDNIGHT)?,
        local_edge(&tz, next, Time::MIDNIGHT)?,
    ))
}

/// Unix seconds of `date` at `t` on the wall clock of `tz` (compatible disambiguation).
fn local_edge(tz: &TimeZone, date: Date, t: Time) -> Result<i64, BirthError> {
    let civil = to_civil(PrimitiveDateTime::new(date, t))?;
    let z = tz
        .to_ambiguous_zoned_datetime(civil)
        .compatible()
        .map_err(|_| BirthError::OutOfRange)?;
    Ok(z.timestamp().as_second())
}

/// `HH:MM` of a unix instant on the wall clock of `zone`, seconds truncated.
pub fn local_hhmm(secs: i64, zone: &str) -> Result<String, BirthError> {
    let tz = TimeZone::get(zone).map_err(|_| BirthError::UnknownZone)?;
    let ts = jiff::Timestamp::from_second(secs).map_err(|_| BirthError::OutOfRange)?;
    let z = tz.to_datetime(ts);
    Ok(format!("{:02}:{:02}", z.hour(), z.minute()))
}

fn check_year(d: Date) -> Result<(), BirthError> {
    if YEARS.contains(&d.year()) {
        Ok(())
    } else {
        Err(BirthError::OutOfRange)
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use time::Month;

    fn at(y: i32, mo: u8, d: u8, h: u8, mi: u8) -> PrimitiveDateTime {
        PrimitiveDateTime::new(
            Date::from_calendar_date(y, Month::try_from(mo).unwrap(), d).unwrap(),
            Time::from_hms(h, mi, 0).unwrap(),
        )
    }

    fn birth(local: PrimitiveDateTime, zone: &str) -> Birth {
        Birth {
            local,
            zone: zone.to_owned(),
            lat: 10.0,
            lon: 100.0,
            time_known: true,
        }
    }

    fn utc(y: i32, mo: u8, d: u8, h: u8, mi: u8) -> i64 {
        at(y, mo, d, h, mi).assume_utc().unix_timestamp()
    }

    #[test]
    fn dst_gap_is_nonexistent() {
        let b = birth(at(2021, 3, 14, 2, 30), "America/New_York");
        assert_eq!(birth_instant(&b), Err(BirthError::Nonexistent));
    }

    #[test]
    fn dst_overlap_takes_the_earlier_instant() {
        let b = birth(at(2021, 11, 7, 1, 30), "America/New_York");
        assert_eq!(birth_instant(&b), Ok(utc(2021, 11, 7, 5, 30)));
    }

    #[test]
    fn south_vietnam_1970_uses_the_historical_offset() {
        let b = birth(at(1970, 6, 1, 12, 0), "Asia/Ho_Chi_Minh");
        assert_eq!(birth_instant(&b), Ok(utc(1970, 6, 1, 4, 0)));
    }

    #[test]
    fn london_summer_time_is_utc_plus_1() {
        let b = birth(at(1990, 7, 15, 12, 0), "Europe/London");
        assert_eq!(birth_instant(&b), Ok(utc(1990, 7, 15, 11, 0)));
    }

    #[test]
    fn unknown_time_uses_local_noon() {
        let mut b = birth(at(1990, 7, 15, 8, 13), "Europe/London");
        b.time_known = false;
        assert_eq!(birth_instant(&b), Ok(utc(1990, 7, 15, 11, 0)));
    }

    #[test]
    fn years_outside_1900_2100_are_out_of_range() {
        for local in [at(1899, 12, 31, 12, 0), at(2101, 1, 1, 12, 0)] {
            assert_eq!(
                birth_instant(&birth(local, "Europe/London")),
                Err(BirthError::OutOfRange)
            );
        }
        assert!(birth_instant(&birth(at(1900, 1, 1, 12, 0), "Europe/London")).is_ok());
        assert!(birth_instant(&birth(at(2100, 12, 31, 12, 0), "Europe/London")).is_ok());
    }

    #[test]
    fn unknown_zone() {
        let b = birth(at(1990, 7, 15, 12, 0), "Mars/Base");
        assert_eq!(birth_instant(&b), Err(BirthError::UnknownZone));
    }

    #[test]
    fn bad_coordinates() {
        let mut b = birth(at(1990, 7, 15, 12, 0), "Europe/London");
        b.lat = 91.0;
        assert_eq!(birth_instant(&b), Err(BirthError::BadLatitude));
        b.lat = f64::NAN;
        assert_eq!(birth_instant(&b), Err(BirthError::BadLatitude));
        b.lat = -90.0;
        b.lon = -181.0;
        assert_eq!(birth_instant(&b), Err(BirthError::BadLongitude));
        b.lon = 180.0;
        assert!(birth_instant(&b).is_ok());
    }

    #[test]
    fn day_bounds_ordinary_day() {
        let d = Date::from_calendar_date(2021, Month::July, 1).unwrap();
        // London BST (UTC+1): 00:00 local is 23:00Z the day before.
        assert_eq!(
            local_day_bounds(d, "Europe/London"),
            Ok((utc(2021, 6, 30, 23, 0), utc(2021, 7, 1, 22, 59) + 59))
        );
    }

    #[test]
    fn day_bounds_on_a_dst_day_span_23_hours() {
        let d = Date::from_calendar_date(2021, Month::March, 14).unwrap();
        let (a, b) = local_day_bounds(d, "America/New_York").unwrap();
        assert_eq!(a, utc(2021, 3, 14, 5, 0));
        assert_eq!(b, utc(2021, 3, 15, 3, 59) + 59);
    }

    #[test]
    fn day_bounds_at_midnight_gap_use_first_valid_instant() {
        // Asia/Beirut 2021-03-28: clocks jump 00:00 -> 01:00 (EET +2 to EEST +3), so
        // 00:00 does not exist; the first valid instant is 01:00 EEST = 22:00Z on 03-27.
        let d = Date::from_calendar_date(2021, Month::March, 28).unwrap();
        let (a, b) = local_day_bounds(d, "Asia/Beirut").unwrap();
        assert_eq!(a, utc(2021, 3, 27, 22, 0));
        assert_eq!(b, utc(2021, 3, 28, 20, 59) + 59);
    }

    #[test]
    fn day_bounds_errors() {
        let d = Date::from_calendar_date(2101, Month::January, 1).unwrap();
        assert_eq!(
            local_day_bounds(d, "Europe/London"),
            Err(BirthError::OutOfRange)
        );
        let d = Date::from_calendar_date(2000, Month::January, 1).unwrap();
        assert_eq!(
            local_day_bounds(d, "Mars/Base"),
            Err(BirthError::UnknownZone)
        );
    }

    #[test]
    fn day_span_ends_at_the_next_local_midnight() {
        let d = Date::from_calendar_date(2021, Month::March, 14).unwrap();
        // New York spring-forward day: 23 hours long.
        assert_eq!(
            local_day_span(d, "America/New_York"),
            Ok((utc(2021, 3, 14, 5, 0), utc(2021, 3, 15, 4, 0)))
        );
        // The last supported day ends at 2101-01-01 00:00 local.
        let d = Date::from_calendar_date(2100, Month::December, 31).unwrap();
        assert_eq!(
            local_day_span(d, "UTC"),
            Ok((utc(2100, 12, 31, 0, 0), utc(2101, 1, 1, 0, 0)))
        );
        let d = Date::from_calendar_date(2101, Month::January, 1).unwrap();
        assert_eq!(local_day_span(d, "UTC"), Err(BirthError::OutOfRange));
        assert_eq!(local_day_span(d, "Mars/Base"), Err(BirthError::UnknownZone));
    }
}
