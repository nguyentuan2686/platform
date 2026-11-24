//! Ports the application owns.

use crate::application::birth::NormalizedBirth;
use crate::domain::chart::{Chart, Flow, Gender};
use time::Date;

/// A Zi Wei Dou Shu engine: casts the natal chart and the flow of a day.
pub trait ZiweiEngine: Send + Sync {
    /// The natal chart for a normalised birth.
    fn chart(&self, b: &NormalizedBirth, g: Gender) -> anyhow::Result<Chart>;

    /// The flow (yearly, monthly, daily layers) of `day` for that chart.
    fn flow(&self, b: &NormalizedBirth, g: Gender, day: Date) -> anyhow::Result<Flow>;
}
