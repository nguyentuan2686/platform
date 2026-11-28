//! Time scales: Julian Day (UT) from Unix time, ΔT, and the dynamical-time `Moment`.

/// Julian Day of the Unix epoch, 1970-01-01 00:00 UT.
const JD_UNIX_EPOCH: f64 = 2_440_587.5;
const SECONDS_PER_DAY: f64 = 86_400.0;

/// Julian Day (UT) of a Unix timestamp in seconds (Meeus ch. 7).
pub fn jd_from_unix(secs: i64) -> f64 {
    #[allow(clippy::cast_precision_loss)] // |secs| for years 1800–2200 is far below 2^52.
    let s = secs as f64;
    JD_UNIX_EPOCH + s / SECONDS_PER_DAY
}

/// ΔT = TT − UT in seconds for a decimal year.
///
/// - Before 2005: Espenak–Meeus 2006 polynomials (NASA "Polynomial expressions for Delta T");
///   before 1860 their long-term parabola.
/// - 2005–2024: piecewise-linear through the observed annual values (USNO/IERS, 1 January).
///   The table starts in 2005 rather than 2016 because that is where the observation-fitted
///   1986–2005 polynomial ends and agrees with observation (64.72 s vs 64.69 s). The
///   2005–2050 polynomial is a prediction that overestimates today's ΔT (69.5 s at 2016 vs
///   68.1 s observed, a 1.4 s jump).
/// - From 2024: constant 69.18 s. Future ΔT is unpredictable and Espenak–Meeus diverges to
///   about 203 s by 2100. JPL Horizons, the golden reference, holds ΔT at its latest value
///   for future dates, and we match that convention.
#[allow(clippy::unreadable_literal)] // coefficients transcribed verbatim from the source
pub fn delta_t_seconds(y: f64) -> f64 {
    if y < 1860.0 {
        long_term(y)
    } else if y < 1900.0 {
        let t = y - 1860.0;
        7.62 + 0.5737 * t - 0.251754 * t.powi(2) + 0.01680668 * t.powi(3) - 0.0004473624 * t.powi(4)
            + t.powi(5) / 233174.0
    } else if y < 1920.0 {
        let t = y - 1900.0;
        -2.79 + 1.494119 * t - 0.0598939 * t.powi(2) + 0.0061966 * t.powi(3) - 0.000197 * t.powi(4)
    } else if y < 1941.0 {
        let t = y - 1920.0;
        21.20 + 0.84493 * t - 0.076100 * t.powi(2) + 0.0020936 * t.powi(3)
    } else if y < 1961.0 {
        let t = y - 1950.0;
        29.07 + 0.407 * t - t.powi(2) / 233.0 + t.powi(3) / 2547.0
    } else if y < 1986.0 {
        let t = y - 1975.0;
        45.45 + 1.067 * t - t.powi(2) / 260.0 - t.powi(3) / 718.0
    } else if y < OBSERVED_START {
        let t = y - 2000.0;
        63.86 + 0.3345 * t - 0.060374 * t.powi(2)
            + 0.0017275 * t.powi(3)
            + 0.000651814 * t.powi(4)
            + 0.00002373599 * t.powi(5)
    } else if y < OBSERVED_END {
        observed(y)
    } else {
        FUTURE_DELTA_T
    }
}

/// Year of the first [`OBSERVED`] value.
const OBSERVED_START: f64 = 2005.0;
/// Year of the last [`OBSERVED`] value; [`FUTURE_DELTA_T`] from here on.
const OBSERVED_END: f64 = 2024.0;
/// ΔT held from 2024 on, in seconds (the JPL Horizons convention for future dates).
const FUTURE_DELTA_T: f64 = 69.18;
/// Observed ΔT on 1 January of each year 2005..=2024, in seconds (USNO/IERS `deltat.data`).
#[rustfmt::skip]
const OBSERVED: [f64; 20] = [
    64.69, 64.85, 65.15, 65.46, 65.78, 66.07, 66.32, 66.60, 66.91, 67.28, // 2005–2014
    67.64, 68.10, 68.59, 68.97, 69.22, 69.36, 69.36, 69.29, 69.20, 69.18, // 2015–2024
];

/// Linear interpolation in [`OBSERVED`], for `OBSERVED_START <= y < OBSERVED_END`.
fn observed(y: f64) -> f64 {
    let x = (y - OBSERVED_START).max(0.0);
    let whole = x.floor();
    // 0 <= whole < 19 for the caller's range; `min` keeps k + 1 in bounds regardless.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let k = (whole as usize).min(OBSERVED.len() - 2);
    #[allow(clippy::cast_precision_loss)] // k < 20
    let frac = x - k as f64;
    OBSERVED[k] + (OBSERVED[k + 1] - OBSERVED[k]) * frac
}

fn long_term(y: f64) -> f64 {
    let u = (y - 1820.0) / 100.0;
    -20.0 + 32.0 * u * u
}

/// An instant on both time scales: Julian Day in UT and in dynamical time (TT).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Moment {
    pub jd_ut: f64,
    pub jde: f64,
}

impl Moment {
    pub fn from_unix(secs: i64) -> Moment {
        let jd_ut = jd_from_unix(secs);
        let decimal_year = 2000.0 + (jd_ut - 2_451_544.5) / 365.25;
        Moment {
            jd_ut,
            jde: jd_ut + delta_t_seconds(decimal_year) / SECONDS_PER_DAY,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn julian_day_of_meeus_example_7a() {
        // 1957-10-04 19:26:24 UT (Sputnik 1).
        assert!((jd_from_unix(-386_310_816) - 2_436_116.31).abs() < 1e-6);
    }

    #[test]
    fn delta_t_matches_espenak_meeus_at_segment_starts() {
        assert!((delta_t_seconds(2000.0) - 63.86).abs() < 0.1);
        assert!((delta_t_seconds(1900.0) - -2.79).abs() < 0.1);
    }

    #[test]
    fn delta_t_follows_the_observed_values() {
        assert!((delta_t_seconds(2020.0) - 69.36).abs() < 0.01);
        assert!((delta_t_seconds(2016.5) - 68.345).abs() < 0.01);
    }

    #[test]
    fn delta_t_is_continuous_at_2005_and_2016() {
        for y in [OBSERVED_START, 2016.0, OBSERVED_END] {
            let jump = delta_t_seconds(y - 0.001) - delta_t_seconds(y);
            assert!(jump.abs() < 0.5, "jump at {y}: {jump} s");
        }
    }

    #[test]
    fn delta_t_is_held_constant_for_future_dates() {
        assert!((delta_t_seconds(2050.0) - 69.18).abs() < 1e-9);
        assert!((delta_t_seconds(2100.0) - 69.18).abs() < 1e-9);
    }

    #[test]
    fn moment_adds_delta_t_to_jd_ut() {
        let m = Moment::from_unix(946_684_800); // 2000-01-01 00:00 UT
        assert!((m.jd_ut - 2_451_544.5).abs() < 1e-9);
        assert!(((m.jde - m.jd_ut) * 86_400.0 - 63.86).abs() < 0.1);
    }
}
