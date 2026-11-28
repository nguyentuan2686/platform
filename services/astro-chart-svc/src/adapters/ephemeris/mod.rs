//! Ephemeris adapter: the Sun and planets from VSOP87D, the Moon and its true node from
//! Meeus ch. 47; Pluto and Chiron interpolated from JPL Horizons tables.

pub mod moon;
mod planets;
pub mod table;

use std::sync::OnceLock;

use anyhow::Context;

use crate::application::ports::EphemerisPort;
use crate::domain::body::{Body, Ecliptic};
use crate::domain::time::Moment;

use planets::Planet;
use table::{Table, TableError};

static PLUTO: OnceLock<Result<Table, TableError>> = OnceLock::new();
static CHIRON: OnceLock<Result<Table, TableError>> = OnceLock::new();

fn tabulated(
    cell: &'static OnceLock<Result<Table, TableError>>,
    text: &str,
    m: Moment,
) -> anyhow::Result<Ecliptic> {
    let table = cell.get_or_init(|| Table::parse(text));
    table
        .as_ref()
        .map_err(Clone::clone)
        .and_then(|t| t.at(m.jde))
        .context("tabulated ephemeris")
}

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
            Body::Pluto => return tabulated(&PLUTO, include_str!("../../../data/pluto.csv"), m),
            Body::Chiron => return tabulated(&CHIRON, include_str!("../../../data/chiron.csv"), m),
        };
        Ok(planets::apparent_planet(planet, m.jde))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epochs_outside_the_tables_are_errors() {
        let m = Moment::from_unix(-5_000_000_000);
        for body in [Body::Pluto, Body::Chiron] {
            assert!(EphemerisAdapter::new().apparent(body, m).is_err());
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
