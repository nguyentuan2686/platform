//! Sample use case behind `GET /v1/info`; replace it with the service's first real one.

use std::sync::Arc;

use time::OffsetDateTime;

use crate::application::birth::{birth_instant, local_day_bounds, Birth, BirthError};
use crate::application::ports::EphemerisPort;
use crate::domain::angles::{angle_diff, norm360, Sign};
use crate::domain::aspects::{natal_aspects, Point, PointPos};
use crate::domain::body::{Body, Ecliptic};
use crate::domain::chart::{
    house_of, round4, AngleOut, AnglesOut, AspectOut, BodyOut, Chart, HouseOut,
};
use crate::domain::houses::{angles, cusps, Angles, HouseError, HouseSystem};
use crate::domain::info::{InfoError, ServiceInfo};
use crate::domain::nutation::{apparent_sidereal_deg, true_obliquity};
use crate::domain::time::Moment;

/// The use cases the HTTP adapter calls (a trait, so tests can inject fakes).
pub trait InfoUseCases: Send + Sync {
    fn info(&self) -> ServiceInfo;
}

/// Framework-free application service.
#[derive(Debug, Clone)]
pub struct InfoService {
    info: ServiceInfo,
}

impl InfoService {
    /// Fails on a blank name, so a misconfigured service stops at startup.
    pub fn new(service_name: &str) -> Result<Self, InfoError> {
        Ok(Self {
            info: ServiceInfo::new(service_name)?,
        })
    }
}

impl InfoUseCases for InfoService {
    fn info(&self) -> ServiceInfo {
        self.info.clone()
    }
}

/// A natal chart request.
#[derive(Debug, Clone, PartialEq)]
pub struct ChartRequest {
    pub birth: Birth,
    pub house_system: HouseSystem,
}

#[derive(Debug, thiserror::Error)]
pub enum ChartError {
    #[error("invalid birth")]
    Birth(#[source] BirthError),
    #[error("ephemeris failure")]
    Ephemeris(#[source] anyhow::Error),
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
            aspects: natal_aspects(&points)
                .into_iter()
                .map(|a| AspectOut {
                    a: a.a.key(),
                    b: a.b.key(),
                    kind: a.kind.key(),
                    orb: round4(a.orb),
                    applying: a.applying,
                })
                .collect(),
        })
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
mod tests {
    use super::*;

    #[test]
    fn info_names_the_service() {
        let service = InfoService::new("svc").unwrap();
        assert_eq!(service.info().name(), "svc");
    }

    #[test]
    fn a_blank_name_is_rejected() {
        assert_eq!(InfoService::new("").unwrap_err(), InfoError::BlankName);
    }
}
