//! Reference sweep: the first solar day of every lunar month 1900-2100, both calendars,
//! against an independent implementation (see `tests/fixtures/lunar/README.md`).
use chart_svc::domain::calendar::{lunar_to_solar, solar_to_lunar, CalendarKind, LunarDate};
use serde_json::Value;
use time::format_description::well_known::Iso8601;
use time::Date;

const FIXTURE: &str = include_str!("fixtures/lunar/vn-1900-2100.json");

/// Rows where Hồ Ngọc Đức's method (truncated Meeus series) and the reference
/// (VSOP87 ephemeris) disagree. Hồ Ngọc Đức's published code disagrees with the
/// reference on exactly these rows too, so they are a property of the method, not of
/// this port. Two causes:
/// - a new moon within about a minute of local midnight: 1944-06-20, 1967-07-07,
///   2072-12-09, 2077-11-15 (VN) and 2097-08-07 (CN);
/// - leap-month placement: in 2033 the reference puts the leap month at 8 (VN) / 7
///   (CN) where the method gives a leap 11th month, which is also what the official
///   Chinese calendar uses; for 2052 the reference gives a leap 12th month (VN).
const KNOWN_DIVERGENCES_VN: &[&str] = &[
    "1944-06-20",
    "1967-07-07",
    "2033-09-23",
    "2033-10-23",
    "2033-11-22",
    "2053-01-20",
    "2072-12-09",
    "2077-11-15",
];
const KNOWN_DIVERGENCES_CN: &[&str] = &[
    "2033-08-25",
    "2033-09-23",
    "2033-10-23",
    "2033-11-22",
    "2097-08-07",
];

fn parse_date(s: &str) -> Date {
    Date::parse(s, &Iso8601::DATE).expect("ISO date")
}

fn check(key: &str, cal: CalendarKind, known: &[&str]) {
    let fixture: Value = serde_json::from_str(FIXTURE).expect("fixture json");
    let rows = fixture[key].as_array().expect("rows");
    assert!(rows.len() > 2400, "{key}: only {} months", rows.len());
    let mut mismatches = vec![];
    for row in rows {
        let iso = row[0].as_str().expect("date");
        let solar = parse_date(iso);
        let expected = LunarDate {
            year: i32::try_from(row[1].as_i64().expect("year")).expect("year"),
            month: u8::try_from(row[2].as_u64().expect("month")).expect("month"),
            day: 1,
            leap: row[3].as_bool().expect("leap"),
        };
        let got = solar_to_lunar(solar, cal);
        if got != expected || lunar_to_solar(expected, cal) != Some(solar) {
            mismatches.push((iso, format!("{iso}: expected {expected:?}, got {got:?}")));
        }
    }
    let dates: Vec<&str> = mismatches.iter().map(|(iso, _)| *iso).collect();
    assert_eq!(
        dates,
        known,
        "{key}: mismatches differ from the documented divergences\n{}",
        mismatches
            .iter()
            .map(|(_, m)| m.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    );
}

#[test]
fn vietnamese_calendar_matches_reference() {
    check("vn", CalendarKind::Vn, KNOWN_DIVERGENCES_VN);
}

#[test]
fn chinese_calendar_matches_reference() {
    check("cn", CalendarKind::Cn, KNOWN_DIVERGENCES_CN);
}

#[test]
fn reference_agrees_with_pinned_tet_dates() {
    let fixture: Value = serde_json::from_str(FIXTURE).expect("fixture json");
    let has = |key: &str, iso: &str, year: i64, month: u64, leap: bool| {
        fixture[key].as_array().expect("rows").iter().any(|r| {
            r[0].as_str() == Some(iso)
                && r[1].as_i64() == Some(year)
                && r[2].as_u64() == Some(month)
                && r[3].as_bool() == Some(leap)
        })
    };
    assert!(has("vn", "1985-01-21", 1985, 1, false));
    assert!(has("cn", "1985-01-21", 1984, 12, false));
    assert!(has("cn", "1985-02-20", 1985, 1, false));
    assert!(has("vn", "1968-01-29", 1968, 1, false));
    assert!(has("cn", "1968-01-30", 1968, 1, false));
    assert!(has("vn", "2007-02-17", 2007, 1, false));
    assert!(has("cn", "2007-02-18", 2007, 1, false));
    assert!(has("vn", "2023-03-22", 2023, 2, true));
}
