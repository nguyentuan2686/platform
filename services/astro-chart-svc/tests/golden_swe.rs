//! Accuracy gate against the Swiss Ephemeris reference (`tests/fixtures/swe-reference.json`):
//! the lunar node, the Ascendant and Midheaven, and the house cusps.

use std::path::PathBuf;

use serde_json::Value;
use time::format_description::well_known::Rfc3339;
use time::OffsetDateTime;

use astro_chart_svc::adapters::ephemeris::moon;
use astro_chart_svc::adapters::ephemeris::EphemerisAdapter;
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::domain::angles::{angle_diff, norm360};
use astro_chart_svc::domain::body::Body;
use astro_chart_svc::domain::houses::{angles, cusps, HouseError, HouseSystem};
use astro_chart_svc::domain::nutation::{apparent_sidereal_deg, nutation, true_obliquity};
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

/// Asc, MC and cusp gate: one arcminute, in arcseconds (spec accuracy gates).
const HOUSES_TOLERANCE_ARCSEC: f64 = 60.0;

/// `(ramc, eps, lat)` for a case: RAMC from apparent sidereal time plus east longitude.
fn house_frame(c: &SweCase) -> (f64, f64, f64) {
    let lon = c.raw["lon"].as_f64().unwrap();
    let lat = c.raw["lat"].as_f64().unwrap();
    let ramc = norm360(apparent_sidereal_deg(c.moment) + lon);
    (ramc, true_obliquity(c.moment.jde), lat)
}

fn ref_cusps(v: &Value) -> Vec<f64> {
    v["cusps"]
        .as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_f64().unwrap())
        .collect()
}

#[test]
fn asc_and_mc_match_reference_within_one_arcminute() {
    let mut asc_err = vec![];
    let mut mc_err = vec![];
    for c in &swe_cases() {
        let (ramc, eps, lat) = house_frame(c);
        let a = angles(ramc, eps, lat);
        // Equal cusp 1 is the Ascendant, also where Placidus is undefined.
        let want_asc = ref_cusps(&c.raw["equal"])[0];
        asc_err.push((c.utc.clone(), angle_diff(a.asc, want_asc) * 3600.0));
        if let Some(mc) = c.raw["placidus"]["mc"].as_f64() {
            mc_err.push((c.utc.clone(), angle_diff(a.mc, mc) * 3600.0));
        }
    }
    check("asc", &asc_err, HOUSES_TOLERANCE_ARCSEC);
    check("mc", &mc_err, HOUSES_TOLERANCE_ARCSEC);
}

#[test]
fn cusps_match_reference_within_one_arcminute() {
    for (sys, key) in [
        (HouseSystem::Placidus, "placidus"),
        (HouseSystem::WholeSign, "wholeSign"),
        (HouseSystem::Equal, "equal"),
    ] {
        let mut errors = vec![];
        for c in &swe_cases() {
            let (ramc, eps, lat) = house_frame(c);
            let got = cusps(sys, ramc, eps, lat, angles(ramc, eps, lat));
            if c.raw[key].is_null() {
                assert_eq!(got, Err(HouseError::Undefined), "{key} at {}", c.utc);
                continue;
            }
            let got = got.unwrap();
            for (i, want) in ref_cusps(&c.raw[key]).iter().enumerate() {
                let label = format!("{} cusp {}", c.utc, i + 1);
                errors.push((label, angle_diff(got[i], *want) * 3600.0));
            }
        }
        check(key, &errors, HOUSES_TOLERANCE_ARCSEC);
    }
}

#[test]
fn cusps_have_house_properties() {
    for c in &swe_cases() {
        let (ramc, eps, lat) = house_frame(c);
        let a = angles(ramc, eps, lat);
        for sys in [
            HouseSystem::Placidus,
            HouseSystem::WholeSign,
            HouseSystem::Equal,
        ] {
            let Ok(k) = cusps(sys, ramc, eps, lat, a) else {
                assert_eq!(sys, HouseSystem::Placidus, "only Placidus may be undefined");
                continue;
            };
            if sys == HouseSystem::Placidus {
                assert!(
                    angle_diff(k[0], a.asc).abs() < 1e-9,
                    "{} cusp 1 = Asc",
                    c.utc
                );
                assert!(
                    angle_diff(k[9], a.mc).abs() < 1e-9,
                    "{} cusp 10 = MC",
                    c.utc
                );
            }
            for i in 0..6 {
                let d = angle_diff(k[i + 6], k[i] + 180.0).abs();
                assert!(d < 1e-9, "{sys:?} {} cusp {} opposite", c.utc, i + 1);
            }
            let unwrapped: Vec<f64> = k.iter().map(|x| norm360(x - k[0])).collect();
            for w in unwrapped.windows(2) {
                assert!(w[0] < w[1], "{sys:?} {} cusps increasing: {k:?}", c.utc);
            }
        }
    }
}
