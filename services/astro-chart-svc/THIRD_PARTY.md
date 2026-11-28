# Third-party data and theory in astro-chart-svc

## vsop87 (planetary positions)

- Crate: `vsop87` 3.0.0, <https://crates.io/crates/vsop87>, MIT or Apache-2.0.
- Implements the VSOP87 planetary theory by P. Bretagnon and G. Francou (Bureau des
  Longitudes, Paris), used for the heliocentric positions of the planets.

## JPL Horizons (accuracy reference)

- Data credit: Solar System Dynamics Group, JPL Horizons, <https://ssd.jpl.nasa.gov/horizons/>.
- Used as the reference for the accuracy tests and as the source of the Pluto and Chiron
  position tables held as fixture data. No JPL code is linked.

## Formula sources

- Jean Meeus, *Astronomical Algorithms*, 2nd ed. (Willmann-Bell, 1998): the formulas for
  nutation, obliquity, sidereal time, aberration, lunar position and the lunar node.
- Espenak and Meeus, polynomial expressions for ΔT (NASA, Five Millennium Canon of Solar
  Eclipses).
