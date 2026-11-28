//! Angle normalisation and zodiac signs.

/// `x` reduced to [0, 360).
pub fn norm360(x: f64) -> f64 {
    let r = x.rem_euclid(360.0);
    // rem_euclid of a tiny negative value rounds up to exactly 360.
    if r >= 360.0 {
        0.0
    } else {
        r
    }
}

/// Signed difference `a − b` in degrees, in (−180, 180].
pub fn angle_diff(a: f64, b: f64) -> f64 {
    let d = norm360(a - b);
    if d > 180.0 {
        d - 360.0
    } else {
        d
    }
}

/// The twelve tropical signs, 30° each from 0° Aries.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Sign {
    Aries,
    Taurus,
    Gemini,
    Cancer,
    Leo,
    Virgo,
    Libra,
    Scorpio,
    Sagittarius,
    Capricorn,
    Aquarius,
    Pisces,
}

impl Sign {
    /// All signs in zodiac order.
    pub const ALL: [Sign; 12] = [
        Sign::Aries,
        Sign::Taurus,
        Sign::Gemini,
        Sign::Cancer,
        Sign::Leo,
        Sign::Virgo,
        Sign::Libra,
        Sign::Scorpio,
        Sign::Sagittarius,
        Sign::Capricorn,
        Sign::Aquarius,
        Sign::Pisces,
    ];

    /// The sign containing ecliptic longitude `lon` and the degrees within it, in [0, 30).
    pub fn of(lon: f64) -> (Sign, f64) {
        let n = norm360(lon);
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)] // n in [0, 360)
        let i = ((n / 30.0).floor() as usize).min(11);
        #[allow(clippy::cast_precision_loss)] // i <= 11
        let deg = n - 30.0 * i as f64;
        (Sign::ALL[i], deg)
    }

    /// Zero-based position in zodiac order (Aries = 0).
    pub fn index(self) -> usize {
        self as usize
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn angle_diff_is_signed_across_zero() {
        assert!((angle_diff(359.95, 0.05) - -0.1).abs() < 1e-9);
        assert!((angle_diff(0.05, 359.95) - 0.1).abs() < 1e-9);
        assert!((angle_diff(10.0, 190.0) - 180.0).abs() < 1e-9);
    }

    #[test]
    fn norm360_wraps_into_range() {
        assert!((norm360(-30.0) - 330.0).abs() < 1e-9);
        assert!((norm360(720.5) - 0.5).abs() < 1e-9);
        assert!(norm360(-1e-15) < 360.0);
    }

    #[test]
    fn sign_of_longitude() {
        assert_eq!(Sign::of(359.99).0, Sign::Pisces);
        assert_eq!(Sign::of(0.0).0, Sign::Aries);
        // -30 deg is 330 deg = 0 deg Pisces; Aquarius spans [300, 330).
        assert_eq!(Sign::of(-30.0).0, Sign::Pisces);
        assert_eq!(Sign::of(-30.01).0, Sign::Aquarius);
        let (s, deg) = Sign::of(45.5);
        assert_eq!(s, Sign::Taurus);
        assert!((deg - 15.5).abs() < 1e-9);
        assert_eq!(Sign::Pisces.index(), 11);
    }
}
