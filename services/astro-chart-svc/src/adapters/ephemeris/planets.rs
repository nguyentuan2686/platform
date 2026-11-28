//! Sun and planets: VSOP87D reduced to apparent geocentric coordinates (Meeus ch. 32, 33).
//!
//! VSOP87D gives heliocentric ecliptic spherical coordinates for the dynamical equinox of date.
//! The reduction: geocentric position with a light-time iteration, FK5 correction (32.3),
//! annual aberration (23.2) and nutation in longitude.

use vsop87::{vsop87d, SphericalCoordinates};

use crate::domain::angles::norm360;
use crate::domain::body::Ecliptic;
use crate::domain::nutation::nutation;

const J2000: f64 = 2_451_545.0;
const DAYS_PER_CENTURY: f64 = 36_525.0;
const ARCSEC: f64 = 1.0 / 3600.0;
/// Light time for one AU, in days (Meeus 33.3).
const LIGHT_TIME_DAYS_PER_AU: f64 = 0.005_775_518_3;
/// Stop iterating once the light time changes by less than this, in days.
const LIGHT_TIME_EPSILON: f64 = 1e-9;
/// Upper bound on light-time iterations; it converges in three or four.
const LIGHT_TIME_MAX_ITER: usize = 20;
/// Constant of aberration κ, in arcseconds.
const ABERRATION_KAPPA: f64 = 20.495_52;

/// The planets VSOP87D covers (besides Earth).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Planet {
    Mercury,
    Venus,
    Mars,
    Jupiter,
    Saturn,
    Uranus,
    Neptune,
}

fn heliocentric(p: Planet, jde: f64) -> SphericalCoordinates {
    match p {
        Planet::Mercury => vsop87d::mercury(jde),
        Planet::Venus => vsop87d::venus(jde),
        Planet::Mars => vsop87d::mars(jde),
        Planet::Jupiter => vsop87d::jupiter(jde),
        Planet::Saturn => vsop87d::saturn(jde),
        Planet::Uranus => vsop87d::uranus(jde),
        Planet::Neptune => vsop87d::neptune(jde),
    }
}

/// Rectangular coordinates of spherical (radians, AU).
fn rectangular(s: &SphericalCoordinates) -> [f64; 3] {
    let (l, b, r) = (s.longitude(), s.latitude(), s.distance());
    [r * b.cos() * l.cos(), r * b.cos() * l.sin(), r * b.sin()]
}

fn centuries(jde: f64) -> f64 {
    (jde - J2000) / DAYS_PER_CENTURY
}

/// Geometric geocentric (λ, β) in degrees, still on the dynamical equinox of date.
fn geometric_geocentric(p: Planet, jde: f64) -> (f64, f64) {
    let earth = rectangular(&vsop87d::earth(jde));
    let mut tau = 0.0;
    let mut geo = [0.0; 3];
    for _ in 0..LIGHT_TIME_MAX_ITER {
        let planet = rectangular(&heliocentric(p, jde - tau));
        geo = [
            planet[0] - earth[0],
            planet[1] - earth[1],
            planet[2] - earth[2],
        ];
        let delta = (geo[0] * geo[0] + geo[1] * geo[1] + geo[2] * geo[2]).sqrt();
        let next = LIGHT_TIME_DAYS_PER_AU * delta;
        let done = (next - tau).abs() < LIGHT_TIME_EPSILON;
        tau = next;
        if done {
            break;
        }
    }
    let lon = norm360(geo[1].atan2(geo[0]).to_degrees());
    let lat = geo[2].atan2(geo[0].hypot(geo[1])).to_degrees();
    (lon, lat)
}

/// FK5 correction (Meeus 32.3), returned as (Δλ, Δβ) in degrees.
fn fk5_correction(lon: f64, lat: f64, t: f64) -> (f64, f64) {
    let lp = (lon - 1.397 * t - 0.000_31 * t * t).to_radians();
    let (sin_lp, cos_lp) = lp.sin_cos();
    let dlon = -0.090_33 + 0.039_16 * (cos_lp + sin_lp) * lat.to_radians().tan();
    let dlat = 0.039_16 * (cos_lp - sin_lp);
    (dlon * ARCSEC, dlat * ARCSEC)
}

/// Annual aberration (Meeus 23.2), returned as (Δλ, Δβ) in degrees. `sun_lon` is the Sun's
/// true geometric longitude in degrees.
#[allow(clippy::unreadable_literal, clippy::many_single_char_names)] // Meeus coefficients and symbols
fn aberration(lon: f64, lat: f64, sun_lon: f64, t: f64) -> (f64, f64) {
    let e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t;
    let perihelion = (102.93735 + 1.71946 * t + 0.00046 * t * t).to_radians();
    let (l, b, s) = (lon.to_radians(), lat.to_radians(), sun_lon.to_radians());
    let k = ABERRATION_KAPPA;
    let dlon = (-k * (s - l).cos() + e * k * (perihelion - l).cos()) / b.cos();
    let dlat = -k * b.sin() * ((s - l).sin() - e * (perihelion - l).sin());
    (dlon * ARCSEC, dlat * ARCSEC)
}

/// Geometric (λ, β) of date → apparent: FK5, aberration, nutation in longitude.
fn apparent_from_geometric(lon: f64, lat: f64, sun_lon: f64, jde: f64) -> Ecliptic {
    let t = centuries(jde);
    let (fk_lon, fk_lat) = fk5_correction(lon, lat, t);
    let (lon, lat) = (lon + fk_lon, lat + fk_lat);
    let (ab_lon, ab_lat) = aberration(lon, lat, sun_lon, t);
    Ecliptic {
        lon: norm360(lon + ab_lon + nutation(jde).dpsi),
        lat: lat + ab_lat,
    }
}

/// The Sun's geometric geocentric (λ, β) of date: Earth + 180°, β negated, in degrees.
fn sun_geometric(jde: f64) -> (f64, f64) {
    let earth = vsop87d::earth(jde);
    let lon = earth.longitude().to_degrees() + 180.0;
    let lat = -earth.latitude().to_degrees();
    (lon, lat)
}

/// Apparent geocentric ecliptic position of the Sun.
pub fn apparent_sun(jde: f64) -> Ecliptic {
    let (lon, lat) = sun_geometric(jde);
    apparent_from_geometric(lon, lat, lon, jde)
}

/// Apparent geocentric ecliptic position of a planet.
pub fn apparent_planet(p: Planet, jde: f64) -> Ecliptic {
    let (lon, lat) = geometric_geocentric(p, jde);
    let (sun_lon, _) = sun_geometric(jde);
    apparent_from_geometric(lon, lat, sun_lon, jde)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::angles::angle_diff;

    fn arcsec(deg: f64) -> f64 {
        deg / ARCSEC
    }

    #[test]
    fn sun_of_meeus_example_25b() {
        // 1992-10-13 0h TD: λ = 199°54′21.818″, β = +0.72″ (Meeus 25.b, full VSOP87).
        let sun = apparent_sun(2_448_908.5);
        let want = 199.0 + 54.0 / 60.0 + 21.818 / 3600.0;
        assert!(arcsec(angle_diff(sun.lon, want)).abs() < 0.5, "{sun:?}");
        assert!((arcsec(sun.lat) - 0.72).abs() < 0.1, "{sun:?}");
    }

    #[test]
    fn venus_of_meeus_example_33a() {
        // 1992-12-20 0h TD, light-time corrected, before FK5, aberration and nutation:
        // λ = 313.08102°, β = −2.08474° (Meeus 33.a, from the truncated VSOP87 of his
        // appendix III, so agreement with the full series is ≈ 1″).
        let (lon, lat) = geometric_geocentric(Planet::Venus, 2_448_976.5);
        assert!(arcsec(angle_diff(lon, 313.081_02)).abs() < 2.0, "{lon}");
        assert!(arcsec(lat - -2.084_74).abs() < 2.0, "{lat}");
        // The apparent position stays within the size of those corrections (≈ 20″).
        let venus = apparent_planet(Planet::Venus, 2_448_976.5);
        assert!(arcsec(angle_diff(venus.lon, lon)).abs() < 30.0, "{venus:?}");
    }

    #[test]
    fn light_time_moves_a_planet_back_along_its_orbit() {
        // Without light time Jupiter would be at its instantaneous position; the iteration
        // shifts it by roughly its motion over ~40 minutes (a few arcseconds), never more.
        let jde = 2_451_545.0;
        let (lon, _) = geometric_geocentric(Planet::Jupiter, jde);
        let earth = rectangular(&vsop87d::earth(jde));
        let jup = rectangular(&vsop87d::jupiter(jde));
        let instant = (jup[1] - earth[1]).atan2(jup[0] - earth[0]).to_degrees();
        let shift = arcsec(angle_diff(lon, instant)).abs();
        assert!(shift > 0.1 && shift < 60.0, "shift {shift}\"");
    }
}
