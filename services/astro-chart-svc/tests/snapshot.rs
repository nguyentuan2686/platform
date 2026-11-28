//! Byte-for-byte snapshot of one chart, so any change in the wire output is deliberate.
//!
//! The snapshot is generated once from the implementation and cross-checked against JPL
//! Horizons ([`snapshot_bodies_agree_with_horizons`]). The byte comparison runs on `x86_64`
//! only (libm differences may move the last printed digit elsewhere); on every architecture
//! two calls give identical bytes.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt as _;
use serde_json::Value;
use tower::ServiceExt as _;

// The service crate in a group of its own: rustfmt sorts within a group, so the order of these
// lines does not depend on the rendered crate name.
use astro_chart_svc::app::{router, AppState};

const REQUEST: &str = r#"{"birth":"1990-07-15T14:30","zone":"Asia/Ho_Chi_Minh","lat":21.0285,"lon":105.8542,"timeKnown":true}"#;
const SNAPSHOT: &str = include_str!("fixtures/snap-1990-07-15.json");
const HORIZONS: &str = include_str!("fixtures/horizons-golden.json");
/// 1 arc minute in degrees.
const ARC_MINUTE: f64 = 1.0 / 60.0;

async fn chart_bytes() -> Vec<u8> {
    let res = router(AppState::new())
        .oneshot(
            Request::post("/astro/chart")
                .header("content-type", "application/json")
                .body(Body::from(REQUEST))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    res.into_body().collect().await.unwrap().to_bytes().to_vec()
}

#[tokio::test]
async fn the_same_request_gives_identical_bytes() {
    assert_eq!(chart_bytes().await, chart_bytes().await);
}

#[cfg(target_arch = "x86_64")]
#[tokio::test]
async fn snapshot_is_byte_identical_on_x86_64() {
    let bytes = chart_bytes().await;
    assert_eq!(String::from_utf8(bytes).unwrap(), SNAPSHOT);
}

/// Shortest angular distance in degrees.
fn separation(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    d.min(360.0 - d)
}

#[tokio::test]
async fn snapshot_bodies_agree_with_horizons() {
    let snap: Value = serde_json::from_str(SNAPSHOT).unwrap();
    let golden: Value = serde_json::from_str(HORIZONS).unwrap();
    assert_eq!(snap["instantUtc"], "1990-07-15T07:30:00Z");
    let case = golden["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["utc"] == snap["instantUtc"])
        .expect("horizons case for the snapshot instant");
    let mut compared = 0;
    for body in snap["bodies"].as_array().unwrap() {
        let key = body["key"].as_str().unwrap();
        // Horizons has no lunar node.
        let Some(reference) = case["bodies"].get(key) else {
            continue;
        };
        let diff = separation(
            body["lon"].as_f64().unwrap(),
            reference["lon"].as_f64().unwrap(),
        );
        assert!(
            diff <= ARC_MINUTE,
            "{key}: {diff} deg from Horizons (limit {ARC_MINUTE})"
        );
        compared += 1;
    }
    assert_eq!(compared, 11, "every body but NorthNode is cross-checked");
}

#[tokio::test]
async fn the_live_response_agrees_with_the_snapshot_within_rounding() {
    // On any architecture: same structure and values within 1e-3 degrees of the snapshot.
    let live: Value = serde_json::from_slice(&chart_bytes().await).unwrap();
    let snap: Value = serde_json::from_str(SNAPSHOT).unwrap();
    assert_eq!(live["instantUtc"], snap["instantUtc"]);
    assert_eq!(live["school"], snap["school"]);
    assert_eq!(live["houses"].as_array().unwrap().len(), 12);
    for (l, s) in live["bodies"]
        .as_array()
        .unwrap()
        .iter()
        .zip(snap["bodies"].as_array().unwrap())
    {
        assert_eq!(l["key"], s["key"]);
        assert_eq!(l["sign"], s["sign"]);
        assert!(separation(l["lon"].as_f64().unwrap(), s["lon"].as_f64().unwrap()) < 1e-3);
    }
}
