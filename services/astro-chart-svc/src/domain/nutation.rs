//! Nutation (IAU 1980, Meeus table 22.A), obliquity and apparent sidereal time.

use crate::domain::angles::norm360;
use crate::domain::time::Moment;

const J2000: f64 = 2_451_545.0;
const DAYS_PER_CENTURY: f64 = 36_525.0;
/// Table 22.A coefficients are in units of 0.0001″.
const TABLE_UNIT_DEG: f64 = 0.0001 / 3600.0;

/// Nutation in longitude (`dpsi`) and in obliquity (`deps`), in degrees.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Nutation {
    pub dpsi: f64,
    pub deps: f64,
}

/// One row of Meeus table 22.A: argument multipliers of D, M, M′, F, Ω, then
/// Δψ = (s0 + s1·T)·sin(arg) and Δε = (c0 + c1·T)·cos(arg), in 0.0001″.
struct Term {
    d: i8,
    m: i8,
    mp: i8,
    f: i8,
    om: i8,
    s0: f64,
    s1: f64,
    c0: f64,
    c1: f64,
}

#[allow(clippy::too_many_arguments)] // one argument per table column
const fn t(d: i8, m: i8, mp: i8, f: i8, om: i8, s0: f64, s1: f64, c0: f64, c1: f64) -> Term {
    Term {
        d,
        m,
        mp,
        f,
        om,
        s0,
        s1,
        c0,
        c1,
    }
}

/// Meeus, Astronomical Algorithms 2nd ed., table 22.A (all 63 terms).
#[rustfmt::skip]
const TABLE: [Term; 63] = [
    t( 0,  0,  0,  0,  1, -171_996.0, -174.2, 92_025.0,  8.9),
    t(-2,  0,  0,  2,  2,  -13_187.0,   -1.6,  5_736.0, -3.1),
    t( 0,  0,  0,  2,  2,   -2_274.0,   -0.2,    977.0, -0.5),
    t( 0,  0,  0,  0,  2,    2_062.0,    0.2,   -895.0,  0.5),
    t( 0,  1,  0,  0,  0,    1_426.0,   -3.4,     54.0, -0.1),
    t( 0,  0,  1,  0,  0,      712.0,    0.1,     -7.0,  0.0),
    t(-2,  1,  0,  2,  2,     -517.0,    1.2,    224.0, -0.6),
    t( 0,  0,  0,  2,  1,     -386.0,   -0.4,    200.0,  0.0),
    t( 0,  0,  1,  2,  2,     -301.0,    0.0,    129.0, -0.1),
    t(-2, -1,  0,  2,  2,      217.0,   -0.5,    -95.0,  0.3),
    t(-2,  0,  1,  0,  0,     -158.0,    0.0,      0.0,  0.0),
    t(-2,  0,  0,  2,  1,      129.0,    0.1,    -70.0,  0.0),
    t( 0,  0, -1,  2,  2,      123.0,    0.0,    -53.0,  0.0),
    t( 2,  0,  0,  0,  0,       63.0,    0.0,      0.0,  0.0),
    t( 0,  0,  1,  0,  1,       63.0,    0.1,    -33.0,  0.0),
    t( 2,  0, -1,  2,  2,      -59.0,    0.0,     26.0,  0.0),
    t( 0,  0, -1,  0,  1,      -58.0,   -0.1,     32.0,  0.0),
    t( 0,  0,  1,  2,  1,      -51.0,    0.0,     27.0,  0.0),
    t(-2,  0,  2,  0,  0,       48.0,    0.0,      0.0,  0.0),
    t( 0,  0, -2,  2,  1,       46.0,    0.0,    -24.0,  0.0),
    t( 2,  0,  0,  2,  2,      -38.0,    0.0,     16.0,  0.0),
    t( 0,  0,  2,  2,  2,      -31.0,    0.0,     13.0,  0.0),
    t( 0,  0,  2,  0,  0,       29.0,    0.0,      0.0,  0.0),
    t(-2,  0,  1,  2,  2,       29.0,    0.0,    -12.0,  0.0),
    t( 0,  0,  0,  2,  0,       26.0,    0.0,      0.0,  0.0),
    t(-2,  0,  0,  2,  0,      -22.0,    0.0,      0.0,  0.0),
    t( 0,  0, -1,  2,  1,       21.0,    0.0,    -10.0,  0.0),
    t( 0,  2,  0,  0,  0,       17.0,   -0.1,      0.0,  0.0),
    t( 2,  0, -1,  0,  1,       16.0,    0.0,     -8.0,  0.0),
    t(-2,  2,  0,  2,  2,      -16.0,    0.1,      7.0,  0.0),
    t( 0,  1,  0,  0,  1,      -15.0,    0.0,      9.0,  0.0),
    t(-2,  0,  1,  0,  1,      -13.0,    0.0,      7.0,  0.0),
    t( 0, -1,  0,  0,  1,      -12.0,    0.0,      6.0,  0.0),
    t( 0,  0,  2, -2,  0,       11.0,    0.0,      0.0,  0.0),
    t( 2,  0, -1,  2,  1,      -10.0,    0.0,      5.0,  0.0),
    t( 2,  0,  1,  2,  2,       -8.0,    0.0,      3.0,  0.0),
    t( 0,  1,  0,  2,  2,        7.0,    0.0,     -3.0,  0.0),
    t(-2,  1,  1,  0,  0,       -7.0,    0.0,      0.0,  0.0),
    t( 0, -1,  0,  2,  2,       -7.0,    0.0,      3.0,  0.0),
    t( 2,  0,  0,  2,  1,       -7.0,    0.0,      3.0,  0.0),
    t( 2,  0,  1,  0,  0,        6.0,    0.0,      0.0,  0.0),
    t(-2,  0,  2,  2,  2,        6.0,    0.0,     -3.0,  0.0),
    t(-2,  0,  1,  2,  1,        6.0,    0.0,     -3.0,  0.0),
    t( 2,  0, -2,  0,  1,       -6.0,    0.0,      3.0,  0.0),
    t( 2,  0,  0,  0,  1,       -6.0,    0.0,      3.0,  0.0),
    t( 0, -1,  1,  0,  0,        5.0,    0.0,      0.0,  0.0),
    t(-2, -1,  0,  2,  1,       -5.0,    0.0,      3.0,  0.0),
    t(-2,  0,  0,  0,  1,       -5.0,    0.0,      3.0,  0.0),
    t( 0,  0,  2,  2,  1,       -5.0,    0.0,      3.0,  0.0),
    t(-2,  0,  2,  0,  1,        4.0,    0.0,      0.0,  0.0),
    t(-2,  1,  0,  2,  1,        4.0,    0.0,      0.0,  0.0),
    t( 0,  0,  1, -2,  0,        4.0,    0.0,      0.0,  0.0),
    t(-1,  0,  1,  0,  0,       -4.0,    0.0,      0.0,  0.0),
    t(-2,  1,  0,  0,  0,       -4.0,    0.0,      0.0,  0.0),
    t( 1,  0,  0,  0,  0,       -4.0,    0.0,      0.0,  0.0),
    t( 0,  0,  1,  2,  0,        3.0,    0.0,      0.0,  0.0),
    t( 0,  0, -2,  2,  2,       -3.0,    0.0,      0.0,  0.0),
    t(-1, -1,  1,  0,  0,       -3.0,    0.0,      0.0,  0.0),
    t( 0,  1,  1,  0,  0,       -3.0,    0.0,      0.0,  0.0),
    t( 0, -1,  1,  2,  2,       -3.0,    0.0,      0.0,  0.0),
    t( 2, -1, -1,  2,  2,       -3.0,    0.0,      0.0,  0.0),
    t( 0,  0,  3,  2,  2,       -3.0,    0.0,      0.0,  0.0),
    t( 2, -1,  0,  2,  2,       -3.0,    0.0,      0.0,  0.0),
];

fn centuries(jd: f64) -> f64 {
    (jd - J2000) / DAYS_PER_CENTURY
}

/// Nutation for a Julian Ephemeris Day (IAU 1980 theory, Meeus ch. 22).
#[allow(clippy::unreadable_literal, clippy::many_single_char_names)] // Meeus coefficients and argument names
pub fn nutation(jde: f64) -> Nutation {
    let t = centuries(jde);
    let (t2, t3) = (t * t, t * t * t);
    let d = (297.85036 + 445_267.111480 * t - 0.0019142 * t2 + t3 / 189_474.0).to_radians();
    let m = (357.52772 + 35_999.050340 * t - 0.0001603 * t2 - t3 / 300_000.0).to_radians();
    let mp = (134.96298 + 477_198.867398 * t + 0.0086972 * t2 + t3 / 56_250.0).to_radians();
    let f = (93.27191 + 483_202.017538 * t - 0.0036825 * t2 + t3 / 327_270.0).to_radians();
    let om = (125.04452 - 1934.136261 * t + 0.0020708 * t2 + t3 / 450_000.0).to_radians();

    let (mut dpsi, mut deps) = (0.0, 0.0);
    for r in &TABLE {
        let arg = f64::from(r.d) * d
            + f64::from(r.m) * m
            + f64::from(r.mp) * mp
            + f64::from(r.f) * f
            + f64::from(r.om) * om;
        dpsi += (r.s0 + r.s1 * t) * arg.sin();
        deps += (r.c0 + r.c1 * t) * arg.cos();
    }
    Nutation {
        dpsi: dpsi * TABLE_UNIT_DEG,
        deps: deps * TABLE_UNIT_DEG,
    }
}

/// Mean obliquity of the ecliptic in degrees (Meeus 22.3, Laskar).
#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from Meeus
pub fn mean_obliquity(jde: f64) -> f64 {
    const C: [f64; 10] = [
        -4680.93, -1.55, 1999.25, -51.38, -249.67, -39.05, 7.12, 27.87, 5.79, 2.45,
    ];
    let u = centuries(jde) / 100.0;
    // Horner over U¹…U¹⁰.
    let poly = C.iter().rev().fold(0.0, |acc, c| (acc + c) * u);
    23.0 + 26.0 / 60.0 + (21.448 + poly) / 3600.0
}

/// True obliquity of date: mean obliquity plus nutation in obliquity, in degrees.
pub fn true_obliquity(jde: f64) -> f64 {
    mean_obliquity(jde) + nutation(jde).deps
}

/// Apparent sidereal time at Greenwich in degrees [0, 360): mean sidereal time
/// (Meeus 12.4, from UT) plus the equation of the equinoxes Δψ·cos ε (from JDE).
#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from Meeus
pub fn apparent_sidereal_deg(m: Moment) -> f64 {
    let t = centuries(m.jd_ut);
    let mean = 280.46061837 + 360.98564736629 * (m.jd_ut - J2000) + 0.000387933 * t * t
        - t * t * t / 38_710_000.0;
    let n = nutation(m.jde);
    let eps = mean_obliquity(m.jde) + n.deps;
    norm360(mean + n.dpsi * eps.to_radians().cos())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::time::Moment;

    const ARCSEC: f64 = 1.0 / 3600.0;

    #[test]
    fn nutation_of_meeus_example_22a() {
        let jde = 2_446_895.5;
        let n = nutation(jde);
        assert!(
            (n.dpsi / ARCSEC - -3.788).abs() < 0.01,
            "dpsi {}",
            n.dpsi / ARCSEC
        );
        assert!(
            (n.deps / ARCSEC - 9.443).abs() < 0.01,
            "deps {}",
            n.deps / ARCSEC
        );
        let eps0 = 23.0 + 26.0 / 60.0 + 27.407 / 3600.0;
        assert!((mean_obliquity(jde) - eps0).abs() / ARCSEC < 0.01);
        assert!((true_obliquity(jde) - (eps0 + n.deps)).abs() / ARCSEC < 0.01);
    }

    #[test]
    fn apparent_sidereal_time_of_meeus_example_12a() {
        // 1987-04-10 00:00 UT.
        let m = Moment::from_unix(545_011_200);
        let expected_s = 13.0 * 3600.0 + 10.0 * 60.0 + 46.1351;
        let got_s = apparent_sidereal_deg(m) / 15.0 * 3600.0;
        assert!((got_s - expected_s).abs() < 0.01, "got {got_s}");
    }
}
