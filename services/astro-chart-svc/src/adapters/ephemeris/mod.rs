//! Ephemeris adapter: the Sun and planets from VSOP87D; the other bodies follow in later tasks.

mod planets;

use anyhow::bail;

use crate::application::ports::EphemerisPort;
use crate::domain::body::{Body, Ecliptic};
use crate::domain::time::Moment;

use planets::Planet;

/// [`EphemerisPort`] over the analytical theories.
#[derive(Debug, Clone, Copy, Default)]
pub struct EphemerisAdapter;

impl EphemerisAdapter {
    pub fn new() -> Self {
        Self
    }
}

impl EphemerisPort for EphemerisAdapter {
    fn apparent(&self, body: Body, m: Moment) -> anyhow::Result<Ecliptic> {
        let planet = match body {
            Body::Sun => return Ok(planets::apparent_sun(m.jde)),
            Body::Mercury => Planet::Mercury,
            Body::Venus => Planet::Venus,
            Body::Mars => Planet::Mars,
            Body::Jupiter => Planet::Jupiter,
            Body::Saturn => Planet::Saturn,
            Body::Uranus => Planet::Uranus,
            Body::Neptune => Planet::Neptune,
            Body::Moon | Body::NorthNode | Body::Pluto | Body::Chiron => {
                bail!("ephemeris for {body:?} is not implemented yet")
            }
        };
        Ok(planets::apparent_planet(planet, m.jde))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unimplemented_bodies_are_errors() {
        let m = Moment::from_unix(0);
        for body in [Body::Moon, Body::NorthNode, Body::Pluto, Body::Chiron] {
            let err = EphemerisAdapter::new().apparent(body, m).unwrap_err();
            assert!(err.to_string().contains(&format!("{body:?}")), "{err}");
        }
    }
}
