//! The Moon (Meeus, Astronomical Algorithms 2nd ed., ch. 47: ELP-2000/82 truncated) and the
//! lunar nodes: mean (47.7) and true (osculating, from r × v).
//!
//! Meeus's λ is geocentric on the mean equinox of date and already includes the constant
//! light-time term; the apparent longitude adds only nutation in longitude.

use crate::domain::angles::norm360;
use crate::domain::body::Ecliptic;
use crate::domain::nutation::nutation;

const J2000: f64 = 2_451_545.0;
const DAYS_PER_CENTURY: f64 = 36_525.0;
/// Σl and Σb are in 0.000001°.
const MICRO_DEG: f64 = 1e-6;
/// Mean distance term of Δ (47), in km; Σr is in 0.001 km.
const MEAN_DISTANCE_KM: f64 = 385_000.56;
/// Half the central-difference step for the Moon's velocity, in days (task brief).
const NODE_HALF_STEP_DAYS: f64 = 0.05;

/// One row of table 47.A: multipliers of D, M, M′, F; Σl (sine, 1e-6°) and Σr (cosine, 1e-3 km).
struct LonDistTerm {
    d: i8,
    m: i8,
    mp: i8,
    f: i8,
    l: f64,
    r: f64,
}

#[allow(clippy::many_single_char_names)] // one argument per table column
const fn lr(d: i8, m: i8, mp: i8, f: i8, l: f64, r: f64) -> LonDistTerm {
    LonDistTerm { d, m, mp, f, l, r }
}

/// One row of table 47.B: multipliers of D, M, M′, F; Σb (sine, 1e-6°).
struct LatTerm {
    d: i8,
    m: i8,
    mp: i8,
    f: i8,
    b: f64,
}

#[allow(clippy::many_single_char_names)] // one argument per table column
const fn lb(d: i8, m: i8, mp: i8, f: i8, b: f64) -> LatTerm {
    LatTerm { d, m, mp, f, b }
}

/// Table 47.A in full (60 terms).
#[rustfmt::skip]
const TABLE_A: [LonDistTerm; 60] = [
    lr(0,  0,  1,  0,  6_288_774.0, -20_905_355.0),
    lr(2,  0, -1,  0,  1_274_027.0,  -3_699_111.0),
    lr(2,  0,  0,  0,    658_314.0,  -2_955_968.0),
    lr(0,  0,  2,  0,    213_618.0,    -569_925.0),
    lr(0,  1,  0,  0,   -185_116.0,      48_888.0),
    lr(0,  0,  0,  2,   -114_332.0,      -3_149.0),
    lr(2,  0, -2,  0,     58_793.0,     246_158.0),
    lr(2, -1, -1,  0,     57_066.0,    -152_138.0),
    lr(2,  0,  1,  0,     53_322.0,    -170_733.0),
    lr(2, -1,  0,  0,     45_758.0,    -204_586.0),
    lr(0,  1, -1,  0,    -40_923.0,    -129_620.0),
    lr(1,  0,  0,  0,    -34_720.0,     108_743.0),
    lr(0,  1,  1,  0,    -30_383.0,     104_755.0),
    lr(2,  0,  0, -2,     15_327.0,      10_321.0),
    lr(0,  0,  1,  2,    -12_528.0,           0.0),
    lr(0,  0,  1, -2,     10_980.0,      79_661.0),
    lr(4,  0, -1,  0,     10_675.0,     -34_782.0),
    lr(0,  0,  3,  0,     10_034.0,     -23_210.0),
    lr(4,  0, -2,  0,      8_548.0,     -21_636.0),
    lr(2,  1, -1,  0,     -7_888.0,      24_208.0),
    lr(2,  1,  0,  0,     -6_766.0,      30_824.0),
    lr(1,  0, -1,  0,     -5_163.0,      -8_379.0),
    lr(1,  1,  0,  0,      4_987.0,     -16_675.0),
    lr(2, -1,  1,  0,      4_036.0,     -12_831.0),
    lr(2,  0,  2,  0,      3_994.0,     -10_445.0),
    lr(4,  0,  0,  0,      3_861.0,     -11_650.0),
    lr(2,  0, -3,  0,      3_665.0,      14_403.0),
    lr(0,  1, -2,  0,     -2_689.0,      -7_003.0),
    lr(2,  0, -1,  2,     -2_602.0,           0.0),
    lr(2, -1, -2,  0,      2_390.0,      10_056.0),
    lr(1,  0,  1,  0,     -2_348.0,       6_322.0),
    lr(2, -2,  0,  0,      2_236.0,      -9_884.0),
    lr(0,  1,  2,  0,     -2_120.0,       5_751.0),
    lr(0,  2,  0,  0,     -2_069.0,           0.0),
    lr(2, -2, -1,  0,      2_048.0,      -4_950.0),
    lr(2,  0,  1, -2,     -1_773.0,       4_130.0),
    lr(2,  0,  0,  2,     -1_595.0,           0.0),
    lr(4, -1, -1,  0,      1_215.0,      -3_958.0),
    lr(0,  0,  2,  2,     -1_110.0,           0.0),
    lr(3,  0, -1,  0,       -892.0,       3_258.0),
    lr(2,  1,  1,  0,       -810.0,       2_616.0),
    lr(4, -1, -2,  0,        759.0,      -1_897.0),
    lr(0,  2, -1,  0,       -713.0,      -2_117.0),
    lr(2,  2, -1,  0,       -700.0,       2_354.0),
    lr(2,  1, -2,  0,        691.0,           0.0),
    lr(2, -1,  0, -2,        596.0,           0.0),
    lr(4,  0,  1,  0,        549.0,      -1_423.0),
    lr(0,  0,  4,  0,        537.0,      -1_117.0),
    lr(4, -1,  0,  0,        520.0,      -1_571.0),
    lr(1,  0, -2,  0,       -487.0,      -1_739.0),
    lr(2,  1,  0, -2,       -399.0,           0.0),
    lr(0,  0,  2, -2,       -381.0,      -4_421.0),
    lr(1,  1,  1,  0,        351.0,           0.0),
    lr(3,  0, -2,  0,       -340.0,           0.0),
    lr(4,  0, -3,  0,        330.0,           0.0),
    lr(2, -1,  2,  0,        327.0,           0.0),
    lr(0,  2,  1,  0,       -323.0,       1_165.0),
    lr(1,  1, -1,  0,        299.0,           0.0),
    lr(2,  0,  3,  0,        294.0,           0.0),
    lr(2,  0, -1, -2,          0.0,       8_752.0),
];

/// Table 47.B in full (60 terms).
#[rustfmt::skip]
const TABLE_B: [LatTerm; 60] = [
    lb(0,  0,  0,  1, 5_128_122.0),
    lb(0,  0,  1,  1,   280_602.0),
    lb(0,  0,  1, -1,   277_693.0),
    lb(2,  0,  0, -1,   173_237.0),
    lb(2,  0, -1,  1,    55_413.0),
    lb(2,  0, -1, -1,    46_271.0),
    lb(2,  0,  0,  1,    32_573.0),
    lb(0,  0,  2,  1,    17_198.0),
    lb(2,  0,  1, -1,     9_266.0),
    lb(0,  0,  2, -1,     8_822.0),
    lb(2, -1,  0, -1,     8_216.0),
    lb(2,  0, -2, -1,     4_324.0),
    lb(2,  0,  1,  1,     4_200.0),
    lb(2,  1,  0, -1,    -3_359.0),
    lb(2, -1, -1,  1,     2_463.0),
    lb(2, -1,  0,  1,     2_211.0),
    lb(2, -1, -1, -1,     2_065.0),
    lb(0,  1, -1, -1,    -1_870.0),
    lb(4,  0, -1, -1,     1_828.0),
    lb(0,  1,  0,  1,    -1_794.0),
    lb(0,  0,  0,  3,    -1_749.0),
    lb(0,  1, -1,  1,    -1_565.0),
    lb(1,  0,  0,  1,    -1_491.0),
    lb(0,  1,  1,  1,    -1_475.0),
    lb(0,  1,  1, -1,    -1_410.0),
    lb(0,  1,  0, -1,    -1_344.0),
    lb(1,  0,  0, -1,    -1_335.0),
    lb(0,  0,  3,  1,     1_107.0),
    lb(4,  0,  0, -1,     1_021.0),
    lb(4,  0, -1,  1,       833.0),
    lb(0,  0,  1, -3,       777.0),
    lb(4,  0, -2,  1,       671.0),
    lb(2,  0,  0, -3,       607.0),
    lb(2,  0,  2, -1,       596.0),
    lb(2, -1,  1, -1,       491.0),
    lb(2,  0, -2,  1,      -451.0),
    lb(0,  0,  3, -1,       439.0),
    lb(2,  0,  2,  1,       422.0),
    lb(2,  0, -3, -1,       421.0),
    lb(2,  1, -1,  1,      -366.0),
    lb(2,  1,  0,  1,      -351.0),
    lb(4,  0,  0,  1,       331.0),
    lb(2, -1,  1,  1,       315.0),
    lb(2, -2,  0, -1,       302.0),
    lb(0,  0,  1,  3,      -283.0),
    lb(2,  1,  1, -1,      -229.0),
    lb(1,  1,  0, -1,       223.0),
    lb(1,  1,  0,  1,       223.0),
    lb(0,  1, -2, -1,      -220.0),
    lb(2,  1, -1, -1,      -220.0),
    lb(1,  0,  1,  1,      -185.0),
    lb(2, -1, -2, -1,       181.0),
    lb(0,  1,  2,  1,      -177.0),
    lb(4,  0, -2, -1,       176.0),
    lb(4, -1, -1, -1,       166.0),
    lb(1,  0,  1, -1,      -164.0),
    lb(4,  0,  1, -1,       132.0),
    lb(1,  0, -1, -1,      -119.0),
    lb(4, -1,  0, -1,       115.0),
    lb(2, -2,  0,  1,       107.0),
];

fn centuries(jde: f64) -> f64 {
    (jde - J2000) / DAYS_PER_CENTURY
}

/// Fundamental arguments of the Moon (Meeus 47.1–47.5), in degrees.
struct Args {
    lp: f64,
    d: f64,
    m: f64,
    mp: f64,
    f: f64,
}

#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from Meeus
fn args(t: f64) -> Args {
    let (t2, t3, t4) = (t * t, t * t * t, t * t * t * t);
    Args {
        lp: 218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0,
        d: 297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0,
        m: 357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0,
        mp: 134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0,
        f: 93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0,
    }
}

/// The argument of a table row in radians, and the eccentricity factor E^|m| for it.
#[allow(clippy::many_single_char_names)] // Meeus argument names
fn row(a: &Args, e: f64, d: i8, m: i8, mp: i8, f: i8) -> (f64, f64) {
    let arg = f64::from(d) * a.d + f64::from(m) * a.m + f64::from(mp) * a.mp + f64::from(f) * a.f;
    let factor = match m.unsigned_abs() {
        0 => 1.0,
        1 => e,
        _ => e * e,
    };
    (arg.to_radians(), factor)
}

/// Geocentric (λ, β, Δ) of the Moon: λ and β in degrees on the mean equinox of date, Δ in km.
#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from Meeus
pub fn moon_geocentric(jde: f64) -> (f64, f64, f64) {
    let t = centuries(jde);
    let a = args(t);
    let e = 1.0 - 0.002516 * t - 0.0000074 * t * t;
    let a1 = (119.75 + 131.849 * t).to_radians();
    let a2 = (53.09 + 479264.290 * t).to_radians();
    let a3 = (313.45 + 481266.484 * t).to_radians();
    let (lp, mp, f) = (a.lp.to_radians(), a.mp.to_radians(), a.f.to_radians());

    let (mut sl, mut sr) = (0.0, 0.0);
    for r in &TABLE_A {
        let (arg, factor) = row(&a, e, r.d, r.m, r.mp, r.f);
        sl += r.l * factor * arg.sin();
        sr += r.r * factor * arg.cos();
    }
    let mut sb = 0.0;
    for r in &TABLE_B {
        let (arg, factor) = row(&a, e, r.d, r.m, r.mp, r.f);
        sb += r.b * factor * arg.sin();
    }
    sl += 3958.0 * a1.sin() + 1962.0 * (lp - f).sin() + 318.0 * a2.sin();
    sb += -2235.0 * lp.sin()
        + 382.0 * a3.sin()
        + 175.0 * (a1 - f).sin()
        + 175.0 * (a1 + f).sin()
        + 127.0 * (lp - mp).sin()
        - 115.0 * (lp + mp).sin();

    let lon = norm360(a.lp + sl * MICRO_DEG);
    let lat = sb * MICRO_DEG;
    let dist = MEAN_DISTANCE_KM + sr / 1000.0;
    (lon, lat, dist)
}

/// Apparent geocentric ecliptic position of the Moon: [`moon_geocentric`] plus Δψ.
pub fn moon_apparent(jde: f64) -> Ecliptic {
    let (lon, lat, _) = moon_geocentric(jde);
    Ecliptic {
        lon: norm360(lon + nutation(jde).dpsi),
        lat,
    }
}

/// Longitude of the mean ascending node on the mean equinox of date (Meeus 47.7), in degrees.
#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from Meeus
pub fn mean_node(jde: f64) -> f64 {
    let t = centuries(jde);
    let (t2, t3, t4) = (t * t, t * t * t, t * t * t * t);
    norm360(125.0445479 - 1934.1362891 * t + 0.0020754 * t2 + t3 / 467441.0 - t4 / 60616000.0)
}

/// Rectangular ecliptic-of-date coordinates of the Moon, in km.
fn moon_rectangular(jde: f64) -> [f64; 3] {
    let (lon, lat, dist) = moon_geocentric(jde);
    let (l, b) = (lon.to_radians(), lat.to_radians());
    [
        dist * b.cos() * l.cos(),
        dist * b.cos() * l.sin(),
        dist * b.sin(),
    ]
}

/// Apparent longitude of the true (osculating) ascending node, in degrees: the node of the
/// plane through the Moon's geocentric position r and velocity v (central difference), from
/// h = r × v, plus Δψ.
pub fn true_node(jde: f64) -> f64 {
    let r = moon_rectangular(jde);
    let before = moon_rectangular(jde - NODE_HALF_STEP_DAYS);
    let after = moon_rectangular(jde + NODE_HALF_STEP_DAYS);
    let v: [f64; 3] = std::array::from_fn(|i| (after[i] - before[i]) / (2.0 * NODE_HALF_STEP_DAYS));
    let hx = r[1] * v[2] - r[2] * v[1];
    let hy = r[2] * v[0] - r[0] * v[2];
    norm360(hx.atan2(-hy).to_degrees() + nutation(jde).dpsi)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::angles::angle_diff;

    /// Meeus example 47.a: 1992 April 12, 0h TD.
    const EXAMPLE_JDE: f64 = 2_448_724.5;

    #[test]
    fn geocentric_of_meeus_example_47a() {
        let (lon, lat, dist) = moon_geocentric(EXAMPLE_JDE);
        assert!((lon - 133.162_655).abs() < 1e-6, "λ {lon}");
        assert!((lat - -3.229_126).abs() < 1e-6, "β {lat}");
        assert!((dist - 368_409.7).abs() < 0.1, "Δ {dist}");
    }

    #[test]
    fn apparent_of_meeus_example_47a() {
        let got = moon_apparent(EXAMPLE_JDE);
        assert!(
            (got.lon - 133.167_265).abs() < 1e-5,
            "apparent λ {}",
            got.lon
        );
        assert!((got.lat - -3.229_126).abs() < 1e-6, "β {}", got.lat);
    }

    #[test]
    fn mean_node_of_meeus_example_22a() {
        // 1987 April 10, 0h TD: Ω = 11.2531° (Meeus example 22.a, from the ch. 22 polynomial,
        // which agrees with 47.7 to well under 0.001° near J2000).
        let got = mean_node(2_446_895.5);
        assert!((got - 11.2531).abs() < 1e-3, "Ω {got}");
    }

    #[test]
    fn true_node_oscillates_about_the_mean_node() {
        // The true node departs from the mean node by at most about 1.7°.
        let mut jde = 2_415_020.0;
        while jde < 2_488_070.0 {
            let d = angle_diff(true_node(jde), mean_node(jde));
            assert!(d.abs() < 2.0, "jde {jde}: true − mean = {d}°");
            jde += 97.3;
        }
    }
}
