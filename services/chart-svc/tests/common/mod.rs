//! Helpers shared by the chart integration tests.

use chart_svc::domain::chart::{AdjStarKey, Chart, StarKey};

/// Every star key (major, minor, adjective) is on exactly one palace of the chart; the
/// Vietnamese-only adjective stars are on exactly one palace of a VN chart and on none of a
/// CN chart.
pub fn assert_each_star_once(id: &str, chart: &Chart) {
    let vn = chart.school.starts_with("vn");
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
        // A VN chart relabels Hàm Trì as Đào Hoa (same palace), so it has no Hàm Trì.
        let want = if vn {
            usize::from(star != AdjStarKey::HamTri)
        } else {
            usize::from(!star.is_vn_only())
        };
        assert_eq!(n, want, "{id}: {star:?} appears {n} times, want {want}");
    }
}
