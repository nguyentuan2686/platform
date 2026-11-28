//! Ephemeris adapter: the Sun and planets from VSOP87D, the Moon and its true node from
//! Meeus ch. 47; Pluto and Chiron follow in a later task.

pub mod moon;
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
            Body::Moon => return Ok(moon::moon_apparent(m.jde)),
            Body::NorthNode => {
                return Ok(Ecliptic {
                    lon: moon::true_node(m.jde),
                    lat: 0.0,
                })
            }
            Body::Mercury => Planet::Mercury,
            Body::Venus => Planet::Venus,
            Body::Mars => Planet::Mars,
            Body::Jupiter => Planet::Jupiter,
            Body::Saturn => Planet::Saturn,
            Body::Uranus => Planet::Uranus,
            Body::Neptune => Planet::Neptune,
            Body::Pluto | Body::Chiron => {
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
        for body in [Body::Pluto, Body::Chiron] {
            let err = EphemerisAdapter::new().apparent(body, m).unwrap_err();
            assert!(err.to_string().contains(&format!("{body:?}")), "{err}");
        }
    }

    #[test]
    fn north_node_lies_on_the_ecliptic() {
        let got = EphemerisAdapter::new()
            .apparent(Body::NorthNode, Moment::from_unix(0))
            .unwrap();
        assert!(got.lat.abs() < f64::EPSILON);
        assert!((0.0..360.0).contains(&got.lon));
    }
}
