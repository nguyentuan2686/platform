//! Pluto and Chiron: JPL Horizons apparent positions on a regular TT grid, interpolated.

use crate::domain::angles::norm360;
use crate::domain::body::Ecliptic;

/// Why a Horizons table could not be read or evaluated.
#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum TableError {
    #[error("table header is missing or malformed")]
    Header,
    #[error("table row {line} is malformed")]
    Row { line: usize },
    #[error("table has too few rows")]
    TooShort,
    #[error("epoch is outside the interpolation range of the table")]
    OutOfRange,
}

/// Equally spaced apparent (lon, lat) rows starting at `jde_start`.
#[derive(Debug, Clone)]
pub struct Table {
    jde_start: f64,
    step: f64,
    rows: Vec<(f64, f64)>,
}

fn header_value(header: &str, key: &str) -> Option<f64> {
    header
        .split_whitespace()
        .find_map(|t| t.strip_prefix(key)?.strip_prefix('='))?
        .parse()
        .ok()
}

impl Table {
    /// Parses `# jde_start=<jde> step_days=<days> ...` then one `lon,lat` row per step.
    pub fn parse(text: &str) -> Result<Table, TableError> {
        let mut lines = text.lines();
        let header = lines
            .next()
            .and_then(|l| l.strip_prefix('#'))
            .ok_or(TableError::Header)?;
        let jde_start = header_value(header, "jde_start").ok_or(TableError::Header)?;
        let step = header_value(header, "step_days").ok_or(TableError::Header)?;
        if !jde_start.is_finite() || !step.is_finite() || step <= 0.0 {
            return Err(TableError::Header);
        }
        let mut rows = Vec::new();
        for (i, line) in lines.enumerate() {
            let row = |line: &str| -> Option<(f64, f64)> {
                let (lon, lat) = line.split_once(',')?;
                let lon = lon.trim().parse::<f64>().ok()?;
                let lat = lat.trim().parse::<f64>().ok()?;
                (lon.is_finite() && lat.is_finite()).then_some((lon, lat))
            };
            rows.push(row(line).ok_or(TableError::Row { line: i + 2 })?);
        }
        if rows.len() < 4 {
            return Err(TableError::TooShort);
        }
        Ok(Table {
            jde_start,
            step,
            rows,
        })
    }

    /// 4-point Lagrange interpolation (two rows each side) at `jde` (TT).
    // Row indices are < 2^20, so the usize/f64 casts are exact; x is range-checked first.
    #[allow(
        clippy::cast_precision_loss,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    pub fn at(&self, jde: f64) -> Result<Ecliptic, TableError> {
        let x = (jde - self.jde_start) / self.step;
        if !x.is_finite() || x < 1.0 || x > (self.rows.len() - 2) as f64 {
            return Err(TableError::OutOfRange);
        }
        // x is in [1, len-2], so floor(x) in [1, len-2] and floor(x)+2 may equal len:
        // the last valid cell is [len-3, len-2], so clamp the base row.
        let i = (x.floor() as usize).min(self.rows.len() - 3);
        let t = x - i as f64; // in [0, 1] relative to row i; nodes at -1, 0, 1, 2
        let nodes = [-1.0, 0.0, 1.0, 2.0];

        let mut lons = [0.0; 4];
        let mut lats = [0.0; 4];
        let mut prev = self.rows[i - 1].0;
        for k in 0..4 {
            let (lon, lat) = self.rows[i - 1 + k];
            // Unwrap onto the branch nearest the previous node.
            prev += ((lon - prev + 180.0).rem_euclid(360.0)) - 180.0;
            lons[k] = prev;
            lats[k] = lat;
        }

        let weight = |k: usize| {
            nodes
                .iter()
                .enumerate()
                .filter(|&(j, _)| j != k)
                .map(|(_, &xj)| (t - xj) / (nodes[k] - xj))
                .product::<f64>()
        };
        let (mut lon, mut lat) = (0.0, 0.0);
        for k in 0..4 {
            let w = weight(k);
            lon += w * lons[k];
            lat += w * lats[k];
        }
        Ok(Ecliptic {
            lon: norm360(lon),
            lat,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synthetic() -> Table {
        Table::parse(
            "# jde_start=100.0 step_days=5 source=test\n\
             356.0,0.0\n357.0,0.5\n358.0,1.0\n2.0,1.5\n3.0,2.0\n4.0,2.5\n",
        )
        .unwrap()
    }

    #[test]
    fn interpolates_across_the_zero_wrap() {
        // Midway between rows 2 (358) and 3 (2): the unwrapped values are linear, so 0.0.
        let got = synthetic().at(100.0 + 12.5).unwrap();
        assert!(got.lon < 1e-9 || got.lon > 360.0 - 1e-9, "{}", got.lon);
        assert!((got.lat - 1.25).abs() < 1e-9);
    }

    #[test]
    fn grid_points_are_reproduced() {
        let got = synthetic().at(100.0 + 10.0).unwrap();
        assert!((got.lon - 358.0).abs() < 1e-9);
    }

    #[test]
    fn bad_row_is_an_error() {
        let err = Table::parse("# jde_start=1 step_days=5\n1.0,2.0\nabc,2.0\n").unwrap_err();
        assert_eq!(err, TableError::Row { line: 3 });
    }

    #[test]
    fn bad_header_is_an_error() {
        assert_eq!(Table::parse("1.0,2.0\n").unwrap_err(), TableError::Header);
        assert_eq!(
            Table::parse("# step_days=5\n1,1\n").unwrap_err(),
            TableError::Header
        );
    }

    #[test]
    fn short_table_is_an_error() {
        assert_eq!(
            Table::parse("# jde_start=1 step_days=5\n1,1\n").unwrap_err(),
            TableError::TooShort
        );
    }

    #[test]
    fn out_of_range_outside_the_four_point_window() {
        let t = synthetic();
        assert_eq!(t.at(100.0 + 4.9).unwrap_err(), TableError::OutOfRange);
        assert!(t.at(100.0 + 5.0).is_ok());
        assert!(t.at(100.0 + 20.0).is_ok());
        assert_eq!(t.at(100.0 + 20.1).unwrap_err(), TableError::OutOfRange);
        assert_eq!(t.at(f64::NAN).unwrap_err(), TableError::OutOfRange);
    }
}
