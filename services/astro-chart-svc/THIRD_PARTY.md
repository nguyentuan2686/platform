# Third-party data and theory in astro-chart-svc

## vsop87 (planetary positions)

- Crate: `vsop87` 3.0.0, <https://crates.io/crates/vsop87>, MIT or Apache-2.0.
- Implements the VSOP87 planetary theory by P. Bretagnon and G. Francou (Bureau des
  Longitudes, Paris), used for the heliocentric positions of the planets.

## JPL Horizons (accuracy reference)

- Data credit: Solar System Dynamics Group, JPL Horizons, <https://ssd.jpl.nasa.gov/horizons/>.
- Used as the reference for the accuracy tests and as the source of the Pluto and Chiron
  position tables (`data/pluto.csv`, `data/chiron.csv`), which are compiled into the
  service binary (`include_str!`) and interpolated at runtime. No JPL code is linked.

## USNO / IERS (observed ΔT)

- Data credit: U.S. Naval Observatory and the International Earth Rotation and Reference
  Systems Service (IERS), observed ΔT (`deltat.data`), <https://maia.usno.navy.mil/>.
- The observed 1 January values 2005-2024 are compiled into `src/domain/time.rs`.

## Formula sources

- Jean Meeus, *Astronomical Algorithms*, 2nd ed. (Willmann-Bell, 1998): the formulas for
  nutation, obliquity, sidereal time, aberration, lunar position and the lunar node.
- Espenak and Meeus, polynomial expressions for ΔT (NASA, Five Millennium Canon of Solar
  Eclipses).
