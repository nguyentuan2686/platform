//! Ascendant, Midheaven and house cusps (Placidus, whole sign, equal).
//!
//! All angles are in degrees. `ramc` is the right ascension of the meridian: apparent
//! sidereal time plus the geographic longitude (east positive); `eps` is the true
//! obliquity of date; `lat` is the geographic latitude (north positive).

use super::angles::{angle_diff, norm360, Sign};

/// Placidus iteration stops once a cusp moves less than this, in degrees.
const PLACIDUS_TOLERANCE_DEG: f64 = 1e-7;
/// Placidus gives up (`Undefined`) after this many iterations per cusp.
const PLACIDUS_MAX_ITERATIONS: usize = 50;

/// A supported house system.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HouseSystem {
    Placidus,
    WholeSign,
    Equal,
}

impl HouseSystem {
    /// The stable wire key.
    pub fn as_key(self) -> &'static str {
        match self {
            HouseSystem::Placidus => "placidus",
            HouseSystem::WholeSign => "whole-sign",
            HouseSystem::Equal => "equal",
        }
    }

    /// The system named by a wire key, if any.
    pub fn parse(key: &str) -> Option<Self> {
        match key {
            "placidus" => Some(HouseSystem::Placidus),
            "whole-sign" => Some(HouseSystem::WholeSign),
            "equal" => Some(HouseSystem::Equal),
            _ => None,
        }
    }
}

/// The ecliptic longitudes of the Ascendant and the Midheaven.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Angles {
    pub asc: f64,
    pub mc: f64,
}

/// Why a house system has no cusps for a given time and place.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HouseError {
    /// Placidus at or beyond the polar circle, or its iteration did not converge.
    Undefined,
}

/// Ascendant (the eastern horizon point of the ecliptic) and Midheaven.
pub fn angles(ramc: f64, eps: f64, lat: f64) -> Angles {
    let (sr, cr) = ramc.to_radians().sin_cos();
    let (se, ce) = eps.to_radians().sin_cos();
    let tp = lat.to_radians().tan();
    let mc = norm360(sr.atan2(cr * ce).to_degrees());
    let asc = norm360(cr.atan2(-(sr * ce + tp * se)).to_degrees());
    Angles { asc, mc }
}

/// The twelve cusps, cusp 1 first, each in [0, 360).
pub fn cusps(
    sys: HouseSystem,
    ramc: f64,
    eps: f64,
    lat: f64,
    a: Angles,
) -> Result<[f64; 12], HouseError> {
    match sys {
        HouseSystem::Placidus => placidus(ramc, eps, lat, a),
        HouseSystem::WholeSign => Ok(whole_sign_cusps(a.asc)),
        HouseSystem::Equal => Ok(from_first(a.asc)),
    }
}

/// Whole-sign cusps: house 1 is the whole sign containing `first_point` (the Ascendant, or
/// the Sun for the solar basis). Defined everywhere, so it cannot fail.
pub fn whole_sign_cusps(first_point: f64) -> [f64; 12] {
    #[allow(clippy::cast_precision_loss)] // sign index <= 11
    let start = 30.0 * Sign::of(first_point).0.index() as f64;
    from_first(start)
}

/// Twelve cusps 30° apart starting at `first`.
fn from_first(first: f64) -> [f64; 12] {
    let mut c = [0.0; 12];
    for (i, v) in c.iter_mut().enumerate() {
        #[allow(clippy::cast_precision_loss)] // i <= 11
        let step = 30.0 * i as f64;
        *v = norm360(first + step);
    }
    c
}

/// Placidus: cusps 11 and 12 trisect the diurnal semi-arc east of the MC, cusps 2 and 3
/// the nocturnal semi-arc below the eastern horizon; the rest are their opposites.
fn placidus(ramc: f64, eps: f64, lat: f64, a: Angles) -> Result<[f64; 12], HouseError> {
    if lat.abs() >= 90.0 - eps {
        return Err(HouseError::Undefined);
    }
    let equal = from_first(a.asc);
    let mut c = [0.0; 12];
    c[0] = a.asc;
    c[3] = norm360(a.mc + 180.0);
    // (cusp index, semi-arc fraction, above the horizon)
    for (i, f, above) in [
        (10, 1.0 / 3.0, true),
        (11, 2.0 / 3.0, true),
        (1, 2.0 / 3.0, false),
        (2, 1.0 / 3.0, false),
    ] {
        c[i] = placidus_cusp(ramc, eps, lat, f, above, equal[i])?;
    }
    // Opposites: cusps 7-10 from 1-4, cusps 5-6 from 11-12.
    for i in 0..4 {
        c[i + 6] = norm360(c[i] + 180.0);
    }
    for i in 10..12 {
        c[i - 6] = norm360(c[i] + 180.0);
    }
    Ok(c)
}

/// One Placidus cusp by fixed-point iteration from the longitude `guess`.
///
/// Above the horizon the cusp's hour angle east of the meridian is `f`·DSA; below it the
/// cusp lies `f`·NSA east of the lower meridian (cusp 3 at `f` = 1/3, cusp 2 at 2/3).
/// DSA = 90° + AD and NSA = 90° − AD, with the ascensional difference
/// AD = asin(tan φ · tan δ) for the declination δ of the current estimate.
fn placidus_cusp(
    ramc: f64,
    eps: f64,
    lat: f64,
    f: f64,
    above: bool,
    guess: f64,
) -> Result<f64, HouseError> {
    let (se, ce) = eps.to_radians().sin_cos();
    let tp = lat.to_radians().tan();
    let mut lon = guess;
    for _ in 0..PLACIDUS_MAX_ITERATIONS {
        let decl = (se * lon.to_radians().sin()).asin();
        let x = tp * decl.tan();
        if x.abs() > 1.0 {
            return Err(HouseError::Undefined);
        }
        let ad = x.asin().to_degrees();
        let ra = if above {
            ramc + f * (90.0 + ad)
        } else {
            ramc + 180.0 - f * (90.0 - ad)
        };
        let (sr, cr) = ra.to_radians().sin_cos();
        let next = norm360(sr.atan2(cr * ce).to_degrees());
        let moved = angle_diff(next, lon).abs();
        lon = next;
        if moved < PLACIDUS_TOLERANCE_DEG {
            return Ok(lon);
        }
    }
    Err(HouseError::Undefined)
}

#[cfg(test)]
mod tests {
    use super::*;

    const EPS: f64 = 23.44;

    fn ok_placidus(ramc: f64, lat: f64) -> Result<[f64; 12], HouseError> {
        let a = angles(ramc, EPS, lat);
        cusps(HouseSystem::Placidus, ramc, EPS, lat, a)
    }

    #[test]
    fn keys_round_trip() {
        for s in [
            HouseSystem::Placidus,
            HouseSystem::WholeSign,
            HouseSystem::Equal,
        ] {
            assert_eq!(HouseSystem::parse(s.as_key()), Some(s));
        }
        assert_eq!(HouseSystem::WholeSign.as_key(), "whole-sign");
        assert_eq!(HouseSystem::parse("koch"), None);
    }

    #[test]
    fn mc_at_ramc_zero_is_aries_and_asc_is_east() {
        let a = angles(0.0, EPS, 0.0);
        assert!(angle_diff(a.mc, 0.0).abs() < 1e-9);
        // At the equator with RAMC 0 the Ascendant is 0° Cancer.
        assert!(angle_diff(a.asc, 90.0).abs() < 1e-9);
        let d = angle_diff(a.asc, a.mc);
        assert!(d > 0.0 && d < 180.0, "Asc east of MC");
    }

    #[test]
    fn placidus_defined_inside_polar_circle() {
        for ramc in (0..360).step_by(15) {
            assert!(ok_placidus(f64::from(ramc), 66.0).is_ok(), "ramc {ramc}");
        }
    }

    #[test]
    fn placidus_undefined_beyond_polar_circle() {
        assert_eq!(ok_placidus(100.0, 69.65), Err(HouseError::Undefined));
        assert_eq!(ok_placidus(100.0, -69.65), Err(HouseError::Undefined));
    }

    #[test]
    fn whole_sign_and_equal_from_asc() {
        let a = Angles {
            asc: 125.5,
            mc: 30.0,
        };
        let w = cusps(HouseSystem::WholeSign, 0.0, EPS, 0.0, a).unwrap();
        assert!((w[0] - 120.0).abs() < 1e-9 && (w[11] - 90.0).abs() < 1e-9);
        let e = cusps(HouseSystem::Equal, 0.0, EPS, 0.0, a).unwrap();
        assert!((e[0] - 125.5).abs() < 1e-9 && (e[8] - 5.5).abs() < 1e-9);
    }
}
