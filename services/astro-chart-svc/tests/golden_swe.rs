//! Accuracy gate against the Swiss Ephemeris reference (`tests/fixtures/swe-reference.json`):
//! the lunar node here; house cusps share the same fixture loader.

use std::path::PathBuf;

use serde_json::Value;
use time::format_description::well_known::Rfc3339;
use time::OffsetDateTime;

use astro_chart_svc::adapters::ephemeris::moon;
use astro_chart_svc::adapters::ephemeris::EphemerisAdapter;
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::domain::angles::angle_diff;
use astro_chart_svc::domain::body::Body;
use astro_chart_svc::domain::nutation::nutation;
use astro_chart_svc::domain::time::Moment;

/// True node gate: one arcminute, in arcseconds (spec §4.3).
const TRUE_NODE_TOLERANCE_ARCSEC: f64 = 60.0;
/// Mean node gate: half an arcminute, in arcseconds.
const MEAN_NODE_TOLERANCE_ARCSEC: f64 = 30.0;

/// One reference case: the instant and place, with the reference values in `raw`.
struct SweCase {
    utc: String,
    moment: Moment,
    raw: Value,
}

fn swe_cases() -> Vec<SweCase> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/swe-reference.json");
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let cases: Vec<SweCase> = doc["cases"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| {
            let utc = c["utc"].as_str().unwrap().to_owned();
            let secs = OffsetDateTime::parse(&utc, &Rfc3339)
                .unwrap()
                .unix_timestamp();
            SweCase {
                utc,
                moment: Moment::from_unix(secs),
                raw: c.clone(),
            }
        })
        .collect();
    assert!(!cases.is_empty(), "no reference cases");
    cases
}

/// `(utc, error in arcsec)` for every case, printing the max; then asserts each within `tol`.
fn check(label: &str, errors: &[(String, f64)], tol_arcsec: f64) {
    let max = errors.iter().map(|(_, e)| e.abs()).fold(0.0, f64::max);
    println!(
        "{label}: max error vs Swiss Ephemeris over {} cases: {max:.2}\"",
        errors.len()
    );
    for (utc, e) in errors {
        assert!(e.abs() <= tol_arcsec, "{label} at {utc}: {e:.2}\"");
    }
}

#[test]
fn true_node_matches_reference_within_one_arcminute() {
    let eph = EphemerisAdapter::new();
    let errors: Vec<(String, f64)> = swe_cases()
        .iter()
        .map(|c| {
            let got = eph.apparent(Body::NorthNode, c.moment).unwrap();
            assert!(
                got.lat.abs() < f64::EPSILON,
                "the node lies on the ecliptic"
            );
            let want = c.raw["trueNode"].as_f64().unwrap();
            (c.utc.clone(), angle_diff(got.lon, want) * 3600.0)
        })
        .collect();
    check("trueNode", &errors, TRUE_NODE_TOLERANCE_ARCSEC);
}

#[test]
fn mean_node_matches_reference_within_half_an_arcminute() {
    // The reference is apparent of date, so the mean-equinox node gets nutation in longitude.
    let errors: Vec<(String, f64)> = swe_cases()
        .iter()
        .map(|c| {
            let jde = c.moment.jde;
            let got = moon::mean_node(jde) + nutation(jde).dpsi;
            let want = c.raw["meanNode"].as_f64().unwrap();
            (c.utc.clone(), angle_diff(got, want) * 3600.0)
        })
        .collect();
    check("meanNode", &errors, MEAN_NODE_TOLERANCE_ARCSEC);
}
