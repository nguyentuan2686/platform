//! The chart bodies and their ecliptic position.

/// A chart body. The declaration order is the fixed output order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Body {
    Sun,
    Moon,
    Mercury,
    Venus,
    Mars,
    Jupiter,
    Saturn,
    Uranus,
    Neptune,
    Pluto,
    NorthNode,
    Chiron,
}

impl Body {
    /// All bodies in the fixed output order.
    pub const ALL: [Body; 12] = [
        Body::Sun,
        Body::Moon,
        Body::Mercury,
        Body::Venus,
        Body::Mars,
        Body::Jupiter,
        Body::Saturn,
        Body::Uranus,
        Body::Neptune,
        Body::Pluto,
        Body::NorthNode,
        Body::Chiron,
    ];
}

/// Geocentric ecliptic coordinates of date, in degrees: `lon` in [0, 360), `lat` in [−90, 90].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Ecliptic {
    pub lon: f64,
    pub lat: f64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_lists_the_twelve_bodies_in_declaration_order() {
        assert_eq!(Body::ALL.len(), 12);
        for (i, b) in Body::ALL.iter().enumerate() {
            assert_eq!(*b as usize, i);
        }
        assert_eq!(Body::ALL[0], Body::Sun);
        assert_eq!(Body::ALL[10], Body::NorthNode);
        assert_eq!(Body::ALL[11], Body::Chiron);
    }
}
