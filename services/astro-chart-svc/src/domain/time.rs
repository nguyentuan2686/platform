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

/// ΔT = TT − UT in seconds for a decimal year (Espenak–Meeus 2006 polynomials,
/// NASA "Polynomial expressions for Delta T"). Outside 1860–2150 the long-term
/// parabola is used.
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
    } else if y < 2005.0 {
        let t = y - 2000.0;
        63.86 + 0.3345 * t - 0.060374 * t.powi(2)
            + 0.0017275 * t.powi(3)
            + 0.000651814 * t.powi(4)
            + 0.00002373599 * t.powi(5)
    } else if y < 2050.0 {
        let t = y - 2000.0;
        62.92 + 0.32217 * t + 0.005589 * t.powi(2)
    } else if y < 2150.0 {
        long_term(y) - 0.5628 * (2150.0 - y)
    } else {
        long_term(y)
    }
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
    fn moment_adds_delta_t_to_jd_ut() {
        let m = Moment::from_unix(946_684_800); // 2000-01-01 00:00 UT
        assert!((m.jd_ut - 2_451_544.5).abs() < 1e-9);
        assert!(((m.jde - m.jd_ut) * 86_400.0 - 63.86).abs() < 0.1);
    }
}
