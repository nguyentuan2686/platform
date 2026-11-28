//! Ports the application owns; adapters implement them.

use crate::domain::body::{Body, Ecliptic};
use crate::domain::time::Moment;

/// Source of body positions.
pub trait EphemerisPort: Send + Sync {
    /// Apparent geocentric ecliptic longitude and latitude of date (light-time, aberration,
    /// nutation applied), in degrees.
    fn apparent(&self, body: Body, m: Moment) -> anyhow::Result<Ecliptic>;
}
