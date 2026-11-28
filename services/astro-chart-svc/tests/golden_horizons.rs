//! Accuracy gate: apparent geocentric ecliptic positions against JPL Horizons (quantity 31).

use std::path::PathBuf;

use serde_json::Value;
use time::format_description::well_known::Rfc3339;
use time::OffsetDateTime;

use astro_chart_svc::adapters::ephemeris::EphemerisAdapter;
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::domain::angles::angle_diff;
use astro_chart_svc::domain::body::{Body, Ecliptic};
use astro_chart_svc::domain::time::Moment;

/// The gate: one arcminute, in degrees.
const TOLERANCE_DEG: f64 = 1.0 / 60.0;

const SUN_AND_PLANETS: [Body; 8] = [
    Body::Sun,
    Body::Mercury,
    Body::Venus,
    Body::Mars,
    Body::Jupiter,
    Body::Saturn,
    Body::Uranus,
    Body::Neptune,
];

fn golden_cases() -> Vec<Value> {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/horizons-golden.json");
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    doc["cases"].as_array().unwrap().clone()
}

fn moment_of(utc: &str) -> Moment {
    Moment::from_unix(
        OffsetDateTime::parse(utc, &Rfc3339)
            .unwrap()
            .unix_timestamp(),
    )
}

fn want_of(case: &Value, body: Body) -> Ecliptic {
    let b = &case["bodies"][format!("{body:?}")];
    Ecliptic {
        lon: b["lon"].as_f64().unwrap(),
        lat: b["lat"].as_f64().unwrap(),
    }
}

/// Longitude and latitude errors in arcseconds (longitude signed across 0°/360°).
fn errors_arcsec(got: Ecliptic, want: Ecliptic) -> (f64, f64) {
    (
        angle_diff(got.lon, want.lon) * 3600.0,
        (got.lat - want.lat) * 3600.0,
    )
}

fn assert_close(body: Body, case: &Value, got: Ecliptic, want: Ecliptic) {
    let (dlon, dlat) = errors_arcsec(got, want);
    let utc = case["utc"].as_str().unwrap();
    assert!(
        dlon.abs() <= TOLERANCE_DEG * 3600.0 && dlat.abs() <= TOLERANCE_DEG * 3600.0,
        "{body:?} at {utc}: dlon {dlon:.2}\" dlat {dlat:.2}\" (got {got:?}, want {want:?})"
    );
}

#[test]
fn sun_and_planets_match_horizons_within_one_arcminute() {
    check_bodies(&SUN_AND_PLANETS);
}

#[test]
fn moon_matches_horizons_within_one_arcminute() {
    check_bodies(&[Body::Moon]);
}

/// Every golden case for `bodies`: prints the max errors, then asserts each within the gate.
fn check_bodies(bodies: &[Body]) {
    let cases = golden_cases();
    assert!(!cases.is_empty(), "no golden cases");
    let eph = EphemerisAdapter::new();

    let mut results = vec![];
    for case in &cases {
        let m = moment_of(case["utc"].as_str().unwrap());
        for &body in bodies {
            let got = eph.apparent(body, m).unwrap();
            results.push((body, case, got, want_of(case, body)));
        }
    }

    println!("max error vs Horizons over {} cases (arcsec):", cases.len());
    for &body in bodies {
        let (mut lon, mut lat) = (0.0_f64, 0.0_f64);
        for (b, _, got, want) in &results {
            if *b == body {
                let (dlon, dlat) = errors_arcsec(*got, *want);
                lon = lon.max(dlon.abs());
                lat = lat.max(dlat.abs());
            }
        }
        println!("  {body:?}: lon {lon:.2}\" lat {lat:.2}\"");
    }

    for (body, case, got, want) in results {
        assert_close(body, case, got, want);
    }
}

#[test]
fn pluto_and_chiron_match_horizons_within_one_arcminute() {
    check_bodies(&[Body::Pluto, Body::Chiron]);
}
