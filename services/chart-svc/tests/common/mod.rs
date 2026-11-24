//! Helpers shared by the chart integration tests.

use chart_svc::domain::chart::{AdjStarKey, Chart, StarKey};

/// Every star key (major, minor, adjective) is on exactly one palace of the chart.
pub fn assert_each_star_once(id: &str, chart: &Chart) {
    for &star in StarKey::ALL {
        let n = chart
            .palaces
            .iter()
            .flat_map(|p| p.major.iter().chain(&p.minor))
            .filter(|st| st.key == star)
            .count();
        assert_eq!(n, 1, "{id}: {star:?} appears {n} times");
    }
    for &star in AdjStarKey::ALL {
        let n = chart
            .palaces
            .iter()
            .flat_map(|p| &p.adjective)
            .filter(|a| **a == star)
            .count();
        assert_eq!(n, 1, "{id}: {star:?} appears {n} times");
    }
}
