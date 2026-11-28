//! The natal chart use case: birth and ephemeris in, wire-ready chart out.

use std::sync::Arc;

use time::{Date, OffsetDateTime, PrimitiveDateTime, Time};

use crate::application::birth::{birth_instant, local_day_bounds, local_hhmm, Birth, BirthError};
use crate::application::ports::EphemerisPort;
use crate::domain::angles::{angle_diff, norm360, Sign};
use crate::domain::aspects::{natal_aspects, transit_aspects, Aspect, Point, PointPos};
use crate::domain::body::{Body, Ecliptic};
use crate::domain::chart::{
    house_of, moon_phase_key, round4, AngleOut, AnglesOut, AspectOut, BodyOut, Chart, HouseOut,
    MoonIngress, TransitMoon, Transits,
};
use crate::domain::houses::{angles, cusps, Angles, HouseError, HouseSystem};
use crate::domain::nutation::{apparent_sidereal_deg, true_obliquity};
use crate::domain::time::Moment;

/// A natal chart request.
#[derive(Debug, Clone, PartialEq)]
pub struct ChartRequest {
    pub birth: Birth,
    pub house_system: HouseSystem,
}

/// A request for the daily transit facts against a natal chart.
#[derive(Debug, Clone, PartialEq)]
pub struct TransitRequest {
    pub natal: ChartRequest,
    /// The local day in `viewer_zone`; the transit moment is 12:00 of it.
    pub date: Date,
    pub viewer_zone: String,
}

#[derive(Debug, thiserror::Error)]
pub enum ChartError {
    #[error("invalid birth")]
    Birth(#[source] BirthError),
    #[error("ephemeris failure")]
    Ephemeris(#[source] anyhow::Error),
    /// The transit day is outside 1900-2100 or its noon does not exist in the viewer zone.
    #[error("invalid transit date")]
    Date,
    #[error("unknown viewer time zone")]
    ViewerZone,
}

impl From<BirthError> for ChartError {
    fn from(e: BirthError) -> Self {
        ChartError::Birth(e)
    }
}

/// Half the central-difference window for speeds, in days.
const HALF_STEP_DAYS: f64 = 0.5;

/// Builds natal charts from an ephemeris.
#[derive(Clone)]
pub struct ChartService {
    eph: Arc<dyn EphemerisPort>,
}

impl ChartService {
    pub fn new(eph: Arc<dyn EphemerisPort>) -> Self {
        Self { eph }
    }

    fn at(&self, body: Body, m: Moment) -> Result<Ecliptic, ChartError> {
        self.eph.apparent(body, m).map_err(ChartError::Ephemeris)
    }

    /// Position and speed (degrees per day, central difference over ±0.5 day).
    fn body_state(&self, body: Body, m: Moment) -> Result<(Ecliptic, f64), ChartError> {
        let shift = |d: f64| Moment {
            jd_ut: m.jd_ut + d,
            jde: m.jde + d,
        };
        let pos = self.at(body, m)?;
        let before = self.at(body, shift(-HALF_STEP_DAYS))?;
        let after = self.at(body, shift(HALF_STEP_DAYS))?;
        let speed = angle_diff(after.lon, before.lon) / (2.0 * HALF_STEP_DAYS);
        Ok((pos, speed))
    }

    /// Whether the Moon changes sign during the birth's local day.
    fn moon_sign_uncertain(&self, birth: &Birth) -> Result<bool, ChartError> {
        let (start, end) = local_day_bounds(birth.local.date(), &birth.zone)?;
        let first = self.at(Body::Moon, Moment::from_unix(start))?;
        let last = self.at(Body::Moon, Moment::from_unix(end))?;
        Ok(Sign::of(first.lon).0 != Sign::of(last.lon).0)
    }

    pub fn chart(&self, req: &ChartRequest) -> Result<Chart, ChartError> {
        let birth = &req.birth;
        let instant = birth_instant(birth)?;
        let m = Moment::from_unix(instant);
        let instant_utc = format_utc(instant)?;

        let mut states = Vec::with_capacity(Body::ALL.len());
        for body in Body::ALL {
            states.push((body, self.body_state(body, m)?));
        }

        // Angles and cusps only when the time of day is known.
        let (used, fallback, angles_and_cusps) = if birth.time_known {
            let (used, fallback, a, c) = place_houses(birth, req.house_system, m);
            (used, fallback, Some((a, c)))
        } else {
            (req.house_system, None, None)
        };
        let moon_sign_uncertain = if birth.time_known {
            false
        } else {
            self.moon_sign_uncertain(birth)?
        };

        let mut points: Vec<PointPos> = states
            .iter()
            .map(|(b, (pos, speed))| PointPos {
                point: Point::Body(*b),
                lon: pos.lon,
                speed: *speed,
            })
            .collect();
        if let Some((a, _)) = &angles_and_cusps {
            points.push(PointPos {
                point: Point::Asc,
                lon: a.asc,
                speed: 0.0,
            });
            points.push(PointPos {
                point: Point::MC,
                lon: a.mc,
                speed: 0.0,
            });
        }

        let cusps_ref = angles_and_cusps.as_ref().map(|(_, c)| c);
        let bodies = states
            .iter()
            .map(|(b, (pos, speed))| {
                let (sign, deg) = Sign::of(pos.lon);
                BodyOut {
                    key: b.key(),
                    lon: round4(pos.lon),
                    lat: round4(pos.lat),
                    sign: sign.key(),
                    deg: round4(deg),
                    speed: round4(*speed),
                    retrograde: *speed < 0.0,
                    house: cusps_ref.map(|c| house_of(pos.lon, c)),
                }
            })
            .collect();
        Ok(Chart {
            school: format!(
                "tropical;houses={};node=true;aspects=ptolemaic-v1",
                used.as_key()
            ),
            instant_utc,
            time_known: birth.time_known,
            house_system: req.house_system.as_key(),
            house_fallback: fallback,
            moon_sign_uncertain,
            bodies,
            angles: angles_and_cusps.as_ref().map(|(a, _)| AnglesOut {
                asc: angle_out(a.asc),
                mc: angle_out(a.mc),
            }),
            houses: cusps_ref.map(|c| {
                c.iter()
                    .enumerate()
                    .map(|(i, cusp)| HouseOut {
                        #[allow(clippy::cast_possible_truncation)] // i <= 11
                        house: i as u8 + 1,
                        cusp: round4(*cusp),
                        sign: Sign::of(*cusp).0.key(),
                    })
                    .collect()
            }),
            aspects: natal_aspects(&points).iter().map(aspect_out).collect(),
        })
    }

    /// Daily facts for `req.date`: the transiting Moon (sign, house, phase), its sign
    /// ingress during the viewer's local day, and the transit aspects to the natal chart.
    pub fn transits(&self, req: &TransitRequest) -> Result<Transits, ChartError> {
        let birth = &req.natal.birth;
        let natal_m = Moment::from_unix(birth_instant(birth)?);
        let tm = Moment::from_unix(transit_instant(req.date, &req.viewer_zone)?);

        let mut natal = Vec::with_capacity(NATAL_TARGETS + 2);
        for body in Body::ALL.into_iter().take(NATAL_TARGETS) {
            let (pos, speed) = self.body_state(body, natal_m)?;
            natal.push(PointPos {
                point: Point::Body(body),
                lon: pos.lon,
                speed,
            });
        }
        let natal_sun = natal[0].lon;
        let cusps = if birth.time_known {
            let (_, _, a, c) = place_houses(birth, req.natal.house_system, natal_m);
            for (point, lon) in [(Point::Asc, a.asc), (Point::MC, a.mc)] {
                natal.push(PointPos {
                    point,
                    lon,
                    speed: 0.0,
                });
            }
            c
        } else {
            solar_cusps(natal_sun)
        };

        let mut transiting = Vec::with_capacity(TRANSITING.len());
        for body in TRANSITING {
            let (pos, speed) = self.body_state(body, tm)?;
            transiting.push(PointPos {
                point: Point::Body(body),
                lon: pos.lon,
                speed,
            });
        }
        let lon_of = |b: Body| transiting.iter().find(|p| p.point == Point::Body(b));
        let (sun, moon) = match (lon_of(Body::Sun), lon_of(Body::Moon)) {
            (Some(s), Some(m)) => (s.lon, m.lon),
            // TRANSITING always holds the Sun and the Moon.
            _ => (0.0, 0.0),
        };
        let (sign, deg) = Sign::of(moon);
        Ok(Transits {
            date: req.date.to_string(),
            moon: TransitMoon {
                sign: sign.key(),
                deg: round4(deg),
                house: house_of(moon, &cusps),
                house_basis: if birth.time_known { "natal" } else { "solar" },
                phase: moon_phase_key(angle_diff(moon, sun)),
            },
            moon_ingress: self.moon_ingress(req.date, &req.viewer_zone)?,
            aspects: transit_aspects(&transiting, &natal)
                .iter()
                .map(aspect_out)
                .collect(),
        })
    }

    /// The first moment of the viewer's local day when the Moon is in a new sign, found by
    /// bisection to the second and shown truncated to the minute; `None` when it stays put.
    fn moon_ingress(&self, date: Date, zone: &str) -> Result<Option<MoonIngress>, ChartError> {
        let (start, end) = local_day_bounds(date, zone)?;
        let sign_at = |t: i64| -> Result<Sign, ChartError> {
            Ok(Sign::of(self.at(Body::Moon, Moment::from_unix(t))?.lon).0)
        };
        let before = sign_at(start)?;
        if sign_at(end)? == before {
            return Ok(None);
        }
        // The Moon stays under 15 degrees a day, so one change of sign at most.
        let (mut lo, mut hi) = (start, end);
        while hi - lo > 1 {
            let mid = lo + (hi - lo) / 2;
            if sign_at(mid)? == before {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Ok(Some(MoonIngress {
            at: local_hhmm(hi, zone)?,
            sign: sign_at(hi)?.key(),
        }))
    }
}

/// The natal targets of a transit: the ten planets, Sun to Pluto.
const NATAL_TARGETS: usize = 10;

/// The transiting bodies.
const TRANSITING: [Body; 5] = [
    Body::Sun,
    Body::Moon,
    Body::Mercury,
    Body::Venus,
    Body::Mars,
];

/// 12:00 of `date` on the wall clock of `zone`, as a unix instant. A noon in a DST gap or a
/// year outside 1900-2100 is a bad date, an unknown zone a bad viewer zone.
fn transit_instant(date: Date, zone: &str) -> Result<i64, ChartError> {
    let noon = Birth {
        local: PrimitiveDateTime::new(
            date,
            Time::from_hms(12, 0, 0).map_err(|_| ChartError::Date)?,
        ),
        zone: zone.to_owned(),
        lat: 0.0,
        lon: 0.0,
        time_known: true,
    };
    birth_instant(&noon).map_err(|e| match e {
        BirthError::UnknownZone => ChartError::ViewerZone,
        _ => ChartError::Date,
    })
}

/// Whole-sign cusps starting at the sign of the natal Sun (the house basis without a birth time).
#[allow(clippy::cast_precision_loss)] // indices <= 11
fn solar_cusps(natal_sun: f64) -> [f64; 12] {
    let first = 30.0 * Sign::of(natal_sun).0.index() as f64;
    std::array::from_fn(|i| norm360(first + 30.0 * i as f64))
}

fn aspect_out(a: &Aspect) -> AspectOut {
    AspectOut {
        a: a.a.key(),
        b: a.b.key(),
        kind: a.kind.key(),
        orb: round4(a.orb),
        applying: a.applying,
    }
}

fn angle_out(lon: f64) -> AngleOut {
    let (sign, deg) = Sign::of(lon);
    AngleOut {
        lon: round4(lon),
        sign: sign.key(),
        deg: round4(deg),
    }
}

/// Angles and cusps of a known-time birth. Placidus that is undefined at this place falls
/// back to whole sign; the first value is the system actually used.
fn place_houses(
    birth: &Birth,
    requested: HouseSystem,
    m: Moment,
) -> (HouseSystem, Option<&'static str>, Angles, [f64; 12]) {
    let ramc = norm360(apparent_sidereal_deg(m) + birth.lon);
    let eps = true_obliquity(m.jde);
    let a = angles(ramc, eps, birth.lat);
    match cusps(requested, ramc, eps, birth.lat, a) {
        Ok(c) => (requested, None, a, c),
        Err(HouseError::Undefined) => {
            let ws = HouseSystem::WholeSign;
            // Whole sign is defined at every latitude.
            let c = cusps(ws, ramc, eps, birth.lat, a).unwrap_or([0.0; 12]);
            (ws, Some(ws.as_key()), a, c)
        }
    }
}

/// RFC 3339 `YYYY-MM-DDTHH:MM:SSZ` of a unix instant.
fn format_utc(secs: i64) -> Result<String, ChartError> {
    let t = OffsetDateTime::from_unix_timestamp(secs).map_err(|_| BirthError::OutOfRange)?;
    Ok(format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        t.year(),
        u8::from(t.month()),
        t.day(),
        t.hour(),
        t.minute(),
        t.second()
    ))
}

#[cfg(test)]
mod chart_tests {
    use super::*;
    use time::macros::datetime;

    type Source = Box<dyn Fn(Body, Moment) -> anyhow::Result<Ecliptic> + Send + Sync>;

    struct FakeEphemeris(Source);

    impl EphemerisPort for FakeEphemeris {
        fn apparent(&self, body: Body, m: Moment) -> anyhow::Result<Ecliptic> {
            (self.0)(body, m)
        }
    }

    /// Every body fixed at 12° apart from the Sun's 10°.
    fn fixed() -> ChartService {
        ChartService::new(Arc::new(FakeEphemeris(Box::new(|b, _| {
            #[allow(clippy::cast_precision_loss)]
            let lon = 10.0 + 12.0 * (b as usize) as f64;
            Ok(Ecliptic { lon, lat: 1.0 })
        }))))
    }

    fn birth(time_known: bool, lat: f64) -> Birth {
        Birth {
            local: datetime!(2000-01-01 12:00),
            zone: "UTC".into(),
            lat,
            lon: 18.96,
            time_known,
        }
    }

    fn req(b: Birth) -> ChartRequest {
        ChartRequest {
            birth: b,
            house_system: HouseSystem::Placidus,
        }
    }

    #[test]
    fn known_time_has_angles_houses_and_a_house_per_body() {
        let c = fixed().chart(&req(birth(true, 50.0))).unwrap();
        assert_eq!(c.instant_utc, "2000-01-01T12:00:00Z");
        assert_eq!(
            c.school,
            "tropical;houses=placidus;node=true;aspects=ptolemaic-v1"
        );
        assert_eq!(c.house_system, "placidus");
        assert_eq!(c.house_fallback, None);
        assert!(!c.moon_sign_uncertain);
        assert_eq!(c.houses.as_ref().unwrap().len(), 12);
        assert!(c.angles.is_some());
        assert_eq!(c.bodies.len(), 12);
        assert!(c.bodies.iter().all(|b| b.house.is_some()));
        assert_eq!(c.bodies[0].key, "Sun");
        assert_eq!(c.bodies[0].sign, "Aries");
        assert!((c.bodies[0].speed).abs() < 1e-9);
        assert!(!c.bodies[0].retrograde);
    }

    #[test]
    fn unknown_time_has_no_angles_houses_or_body_house() {
        let c = fixed().chart(&req(birth(false, 50.0))).unwrap();
        assert!(!c.time_known);
        assert!(c.angles.is_none());
        assert!(c.houses.is_none());
        assert!(c.bodies.iter().all(|b| b.house.is_none()));
        assert!(c.aspects.iter().all(|a| a.a != "Asc" && a.b != "MC"));
        assert_eq!(
            c.school,
            "tropical;houses=placidus;node=true;aspects=ptolemaic-v1"
        );
    }

    #[test]
    fn moon_changing_sign_during_the_day_is_uncertain() {
        let noon = Moment::from_unix(946_728_000).jd_ut; // 2000-01-01 12:00 UTC
        let eph = FakeEphemeris(Box::new(move |b, m| {
            let lon = match b {
                Body::Moon if m.jd_ut < noon => 89.9, // 29.9 Gemini at 00:00
                Body::Moon => 90.5,                   // 0.5 Cancer at 23:59:59
                _ => 10.0,
            };
            Ok(Ecliptic { lon, lat: 0.0 })
        }));
        let c = ChartService::new(Arc::new(eph))
            .chart(&req(birth(false, 50.0)))
            .unwrap();
        assert!(c.moon_sign_uncertain);
        assert_eq!(c.bodies[1].key, "Moon");
    }

    #[test]
    fn moon_staying_in_a_sign_is_certain() {
        let c = fixed().chart(&req(birth(false, 50.0))).unwrap();
        assert!(!c.moon_sign_uncertain);
    }

    #[test]
    fn placidus_beyond_the_polar_limit_falls_back_to_whole_sign() {
        let c = fixed().chart(&req(birth(true, 69.65))).unwrap();
        assert_eq!(c.house_fallback, Some("whole-sign"));
        assert!(c.school.contains("houses=whole-sign"));
        assert_eq!(c.house_system, "placidus");
        let houses = c.houses.unwrap();
        assert_eq!(houses.len(), 12);
        assert!(houses.iter().all(|h| (h.cusp % 30.0).abs() < 1e-9));
    }

    #[test]
    fn speed_is_the_central_difference_and_negative_means_retrograde() {
        let base = Moment::from_unix(946_728_000).jd_ut;
        let eph = FakeEphemeris(Box::new(move |_, m| {
            Ok(Ecliptic {
                lon: 100.0 - 2.0 * (m.jd_ut - base),
                lat: 0.0,
            })
        }));
        let c = ChartService::new(Arc::new(eph))
            .chart(&req(birth(false, 0.0)))
            .unwrap();
        assert!((c.bodies[0].speed - -2.0).abs() < 1e-9);
        assert!(c.bodies[0].retrograde);
    }

    #[test]
    fn speed_across_zero_aries_is_not_a_jump() {
        let base = Moment::from_unix(946_728_000).jd_ut;
        let eph = FakeEphemeris(Box::new(move |_, m| {
            Ok(Ecliptic {
                lon: norm360(0.2 + 1.0 * (m.jd_ut - base)),
                lat: 0.0,
            })
        }));
        let c = ChartService::new(Arc::new(eph))
            .chart(&req(birth(false, 0.0)))
            .unwrap();
        assert!((c.bodies[0].speed - 1.0).abs() < 1e-9);
    }

    #[test]
    fn an_ephemeris_failure_is_reported() {
        let eph = FakeEphemeris(Box::new(|_, _| Err(anyhow::anyhow!("boom"))));
        let err = ChartService::new(Arc::new(eph))
            .chart(&req(birth(true, 50.0)))
            .unwrap_err();
        assert!(matches!(err, ChartError::Ephemeris(_)));
    }

    #[test]
    fn a_bad_birth_is_reported() {
        let mut b = birth(true, 50.0);
        b.zone = "Nowhere/Land".into();
        let err = fixed().chart(&req(b)).unwrap_err();
        assert!(matches!(err, ChartError::Birth(BirthError::UnknownZone)));
    }

    #[test]
    fn output_is_rounded_to_four_decimals() {
        let eph = FakeEphemeris(Box::new(|_, _| {
            Ok(Ecliptic {
                lon: 10.123_456_789,
                lat: 0.000_049,
            })
        }));
        let c = ChartService::new(Arc::new(eph))
            .chart(&req(birth(true, 50.0)))
            .unwrap();
        assert!((c.bodies[0].lon - 10.1235).abs() < 1e-12);
        assert!(c.bodies[0].lat.abs() < 1e-12);
    }
}

#[cfg(test)]
#[allow(clippy::cast_precision_loss)] // body indices <= 11
mod transit_tests {
    use super::*;
    use time::macros::{date, datetime};

    type Source = Box<dyn Fn(Body, Moment) -> Ecliptic + Send + Sync>;

    struct Fake(Source);

    impl EphemerisPort for Fake {
        fn apparent(&self, body: Body, m: Moment) -> anyhow::Result<Ecliptic> {
            Ok((self.0)(body, m))
        }
    }

    fn service(f: impl Fn(Body, Moment) -> Ecliptic + Send + Sync + 'static) -> ChartService {
        ChartService::new(Arc::new(Fake(Box::new(f))))
    }

    fn at_lon(lon: f64) -> Ecliptic {
        Ecliptic { lon, lat: 0.0 }
    }

    fn req(time_known: bool, zone: &str, d: time::Date) -> TransitRequest {
        TransitRequest {
            natal: ChartRequest {
                birth: Birth {
                    local: datetime!(2000-01-01 12:00),
                    zone: "UTC".into(),
                    lat: 50.0,
                    lon: 18.96,
                    time_known,
                },
                house_system: HouseSystem::Placidus,
            },
            date: d,
            viewer_zone: zone.into(),
        }
    }

    /// 2000-01-01 12:00 UTC, the natal instant.
    fn natal_jd() -> f64 {
        Moment::from_unix(946_728_000).jd_ut
    }

    #[test]
    fn unknown_natal_time_uses_solar_whole_sign_houses() {
        // Natal Sun at 100 (Cancer): house 1 = 90..120, so a Moon at 125 is house 2.
        let s = service(|b, _| match b {
            Body::Sun => at_lon(100.0),
            Body::Moon => at_lon(125.0),
            _ => at_lon(300.0),
        });
        let t = s
            .transits(&req(false, "Asia/Ho_Chi_Minh", date!(2026 - 03 - 10)))
            .unwrap();
        assert_eq!(t.date, "2026-03-10");
        assert_eq!(t.moon.house_basis, "solar");
        assert_eq!(t.moon.house, 2);
        assert_eq!(t.moon.sign, "Leo");
        assert!((t.moon.deg - 5.0).abs() < 1e-9);
    }

    #[test]
    fn known_natal_time_uses_the_natal_cusps() {
        let s = service(|b, _| match b {
            Body::Moon => at_lon(125.0),
            _ => at_lon(300.0),
        });
        let t = s
            .transits(&req(true, "UTC", date!(2026 - 03 - 10)))
            .unwrap();
        assert_eq!(t.moon.house_basis, "natal");
        assert!((1..=12).contains(&t.moon.house));
    }

    #[test]
    fn moon_ingress_is_found_to_the_minute_in_viewer_local_time() {
        // The Moon reaches 90 degrees (0 Cancer) at 15:20 Ho Chi Minh time = 08:20Z.
        let cross = Moment::from_unix(1_773_130_800 /* 2026-03-10 08:20:00Z */).jd_ut;
        let s = service(move |b, m| match b {
            Body::Moon => at_lon(90.0 + 13.0 * (m.jd_ut - cross)),
            _ => at_lon(300.0),
        });
        let t = s
            .transits(&req(false, "Asia/Ho_Chi_Minh", date!(2026 - 03 - 10)))
            .unwrap();
        let ing = t.moon_ingress.unwrap();
        assert_eq!(ing.at, "15:20");
        assert_eq!(ing.sign, "Cancer");
        assert_eq!(
            t.moon.sign, "Gemini",
            "at 12:00 the Moon is still before the ingress"
        );
    }

    #[test]
    fn no_ingress_when_the_sign_holds_all_day() {
        let s = service(|_, _| at_lon(100.0));
        let t = s
            .transits(&req(false, "UTC", date!(2026 - 03 - 10)))
            .unwrap();
        assert!(t.moon_ingress.is_none());
    }

    fn venus_case(venus: f64) -> Transits {
        let nj = natal_jd();
        let s = service(move |b, m| match b {
            Body::Moon => at_lon(10.0),
            Body::Venus if (m.jd_ut - nj).abs() < 1.0 => at_lon(venus),
            _ => at_lon(250.0 + 0.001 * (b as usize) as f64),
        });
        s.transits(&req(false, "UTC", date!(2026 - 03 - 10)))
            .unwrap()
    }

    fn moon_venus(t: &Transits) -> Option<&AspectOut> {
        t.aspects.iter().find(|a| a.a == "Moon" && a.b == "Venus")
    }

    #[test]
    fn the_transiting_moon_orb_is_three_degrees() {
        assert!(moon_venus(&venus_case(13.1)).is_none());
        let t = venus_case(12.9);
        let a = moon_venus(&t).unwrap();
        assert_eq!(a.kind, "conjunction");
    }

    #[test]
    fn other_transiting_bodies_use_two_degrees_and_only_the_five_listed_transit() {
        let nj = natal_jd();
        let s = service(move |b, m| {
            let natal = (m.jd_ut - nj).abs() < 1.0;
            match b {
                Body::Mars if !natal => at_lon(50.0),
                Body::Venus if natal => at_lon(52.5),
                Body::Mercury if natal => at_lon(51.9),
                Body::Jupiter if !natal => at_lon(51.0), // not a transiting body
                _ => at_lon(200.0 + 7.0 * (b as usize) as f64),
            }
        });
        let t = s
            .transits(&req(false, "UTC", date!(2026 - 03 - 10)))
            .unwrap();
        let find = |a: &str, b: &str| t.aspects.iter().find(|x| x.a == a && x.b == b);
        assert!(find("Mars", "Venus").is_none(), "2.5 > 2");
        assert!(find("Mars", "Mercury").is_some(), "1.9 <= 2");
        assert!(t.aspects.iter().all(|x| x.a != "Jupiter"));
        assert!(t
            .aspects
            .iter()
            .all(|x| !matches!(x.b, "NorthNode" | "Chiron" | "Asc" | "MC")));
    }

    #[test]
    fn known_natal_time_adds_the_angles_as_targets() {
        let s = service(|_, _| at_lon(10.0));
        let natal = s
            .chart(&req(true, "UTC", date!(2026 - 03 - 10)).natal)
            .unwrap();
        let asc = natal.angles.unwrap().asc.lon;
        let s = service(move |b, m| {
            if matches!(b, Body::Moon) && (m.jd_ut - natal_jd()).abs() > 1.0 {
                at_lon(asc + 1.0)
            } else {
                at_lon(asc + 100.0 + 5.0 * (b as usize) as f64)
            }
        });
        let t = s
            .transits(&req(true, "UTC", date!(2026 - 03 - 10)))
            .unwrap();
        assert!(t
            .aspects
            .iter()
            .any(|x| x.a == "Moon" && x.b == "Asc" && x.kind == "conjunction"));
    }

    #[test]
    fn a_bad_viewer_zone_or_date_is_reported() {
        let s = service(|_, _| at_lon(10.0));
        let e = s
            .transits(&req(false, "Mars/Base", date!(2026 - 03 - 10)))
            .unwrap_err();
        assert!(matches!(e, ChartError::ViewerZone));
        let e = s
            .transits(&req(false, "UTC", date!(2101 - 01 - 01)))
            .unwrap_err();
        assert!(matches!(e, ChartError::Date));
    }

    #[test]
    fn a_bad_natal_birth_is_reported_as_a_birth_error() {
        let s = service(|_, _| at_lon(10.0));
        let mut r = req(false, "UTC", date!(2026 - 03 - 10));
        r.natal.birth.zone = "Nowhere/Land".into();
        let e = s.transits(&r).unwrap_err();
        assert!(matches!(e, ChartError::Birth(BirthError::UnknownZone)));
    }
}
