//! HTTP adapter tests over the in-process router (`tower::ServiceExt::oneshot`).

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt as _;
use serde_json::{json, Value};
use tower::ServiceExt as _;

// The service crate in a group of its own: rustfmt sorts within a group, so the order of these
// lines does not depend on the rendered crate name.
use astro_chart_svc::app::{router, AppState};
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::domain::body::{Body as Planet, Ecliptic};
use astro_chart_svc::domain::time::Moment;

async fn send_to(state: AppState, req: Request<Body>) -> (StatusCode, String, Value) {
    let res = router(state).oneshot(req).await.unwrap();
    let status = res.status();
    let ct = res
        .headers()
        .get("content-type")
        .map_or_else(String::new, |v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, ct, serde_json::from_slice(&bytes).unwrap())
}

async fn send(req: Request<Body>) -> (StatusCode, String, Value) {
    send_to(AppState::new(), req).await
}

async fn get(uri: &str) -> (StatusCode, String, Value) {
    send(Request::get(uri).body(Body::empty()).unwrap()).await
}

fn post_raw(body: &str) -> Request<Body> {
    Request::post("/astro/chart")
        .header("content-type", "application/json")
        .body(Body::from(body.to_owned()))
        .unwrap()
}

async fn post(body: &Value) -> (StatusCode, String, Value) {
    send(post_raw(&body.to_string())).await
}

/// A valid request with `patch` merged over it (a `null` value removes the field).
fn valid_with(patch: &Value) -> Value {
    let mut base = json!({
        "birth": "1990-07-15T14:30",
        "zone": "Asia/Ho_Chi_Minh",
        "lat": 21.0285,
        "lon": 105.8542,
        "timeKnown": true,
    });
    for (k, v) in patch.as_object().unwrap() {
        if v.is_null() {
            base.as_object_mut().unwrap().remove(k);
        } else {
            base[k] = v.clone();
        }
    }
    base
}

/// Asserts a 400 `validation-error` problem whose first error names `field`.
async fn assert_invalid(body: &Value, field: &str) -> Value {
    let (status, ct, problem) = post(body).await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "{body} -> {problem}");
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(problem["type"], "urn:problem:validation-error");
    assert_eq!(problem["errors"][0]["field"], field, "{body} -> {problem}");
    assert!(problem["trace_id"].is_string(), "{problem}");
    problem
}

#[tokio::test]
async fn live_is_up() {
    let (status, _, body) = get("/health/live").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"status": "UP"}));
}

#[tokio::test]
async fn ready_is_up() {
    let (status, _, body) = get("/health/ready").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "UP");
}

#[tokio::test]
async fn the_scaffold_info_route_is_gone() {
    let (status, _, _) = get("/v1/info").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn unknown_route_is_a_not_found_problem() {
    let (status, ct, body) = get("/nope").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(body["type"], "urn:problem:not-found");
    assert_eq!(body["status"], 404);
    assert!(body["trace_id"].is_string(), "{body}");
}

#[tokio::test]
async fn wrong_method_on_a_route_is_a_problem() {
    let req = Request::get("/astro/chart").body(Body::empty()).unwrap();
    let (status, ct, body) = send(req).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(body["detail"], "route GET /astro/chart not found");
    assert!(body["trace_id"].is_string(), "{body}");
}

#[tokio::test]
async fn a_valid_request_returns_a_camel_case_chart() {
    let (status, ct, chart) = post(&valid_with(&json!({}))).await;
    assert_eq!(status, StatusCode::OK, "{chart}");
    assert!(ct.starts_with("application/json"), "{ct}");
    assert_eq!(chart["instantUtc"], "1990-07-15T07:30:00Z");
    assert_eq!(chart["timeKnown"], true);
    assert_eq!(chart["houseSystem"], "placidus");
    assert!(chart.get("houseFallback").is_none());
    assert_eq!(chart["bodies"].as_array().unwrap().len(), 12);
    assert_eq!(chart["bodies"][0]["key"], "Sun");
    assert_eq!(chart["houses"].as_array().unwrap().len(), 12);
    assert!(chart["angles"]["asc"]["lon"].is_number());
}

#[tokio::test]
async fn the_house_system_is_honoured() {
    let (status, _, chart) = post(&valid_with(&json!({"houseSystem": "whole-sign"}))).await;
    assert_eq!(status, StatusCode::OK, "{chart}");
    assert_eq!(chart["houseSystem"], "whole-sign");
    assert!(chart["school"]
        .as_str()
        .unwrap()
        .contains("houses=whole-sign"));
}

#[tokio::test]
async fn an_unknown_time_has_no_angles_or_houses() {
    let (status, _, chart) = post(&valid_with(&json!({"timeKnown": false}))).await;
    assert_eq!(status, StatusCode::OK, "{chart}");
    assert_eq!(chart["timeKnown"], false);
    assert_eq!(chart["instantUtc"], "1990-07-15T05:00:00Z");
    assert!(chart.get("angles").is_none());
    assert!(chart.get("houses").is_none());
    assert!(chart["moonSignUncertain"].is_boolean());
}

#[tokio::test]
async fn an_arctic_birth_falls_back_to_whole_sign() {
    let body = valid_with(&json!({"zone": "Europe/Oslo", "lat": 69.65, "lon": 18.96}));
    let (status, _, chart) = post(&body).await;
    assert_eq!(status, StatusCode::OK, "{chart}");
    assert_eq!(chart["houseSystem"], "placidus");
    assert_eq!(chart["houseFallback"], "whole-sign");
}

// Review Focus 5: every invalid input is a 400 naming its field, never a 500.

#[tokio::test]
async fn impossible_date_is_a_birth_error() {
    assert_invalid(&valid_with(&json!({"birth": "1990-02-30T10:00"})), "birth").await;
}

#[tokio::test]
async fn malformed_birth_strings_are_birth_errors() {
    for birth in [
        "1990-07-15",
        "1990-07-15 14:30",
        "1990-07-15T14:30:00",
        "1990-07-15T14:30Z",
        "1990-07-15T24:00",
        "1990-07-15T14:60",
        "",
    ] {
        assert_invalid(&valid_with(&json!({ "birth": birth })), "birth").await;
    }
    assert_invalid(&valid_with(&json!({"birth": 19_900_715})), "birth").await;
    assert_invalid(&valid_with(&json!({"birth": null})), "birth").await;
}

#[tokio::test]
async fn years_outside_1900_2100_are_birth_errors() {
    for birth in ["1899-12-31T12:00", "2101-01-01T12:00"] {
        assert_invalid(&valid_with(&json!({ "birth": birth })), "birth").await;
    }
    for birth in ["1900-01-01T12:00", "2100-12-31T12:00"] {
        let (status, _, body) = post(&valid_with(&json!({ "birth": birth }))).await;
        assert_eq!(status, StatusCode::OK, "{birth}: {body}");
    }
}

#[tokio::test]
async fn unknown_zone_is_a_zone_error() {
    assert_invalid(&valid_with(&json!({"zone": "Mars/Base"})), "zone").await;
    assert_invalid(&valid_with(&json!({"zone": null})), "zone").await;
    assert_invalid(&valid_with(&json!({"zone": 7})), "zone").await;
}

#[tokio::test]
async fn latitude_out_of_range_is_a_lat_error() {
    assert_invalid(&valid_with(&json!({"lat": 91})), "lat").await;
    assert_invalid(&valid_with(&json!({"lat": -90.5})), "lat").await;
    assert_invalid(&valid_with(&json!({"lat": "21"})), "lat").await;
    assert_invalid(&valid_with(&json!({"lat": null})), "lat").await;
    let (status, _, _) = post(&valid_with(&json!({"lat": 90}))).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn longitude_out_of_range_is_a_lon_error() {
    assert_invalid(&valid_with(&json!({"lon": -181})), "lon").await;
    assert_invalid(&valid_with(&json!({"lon": 180.01})), "lon").await;
    assert_invalid(&valid_with(&json!({"lon": true})), "lon").await;
    assert_invalid(&valid_with(&json!({"lon": null})), "lon").await;
}

#[tokio::test]
async fn unknown_house_system_is_a_house_system_error() {
    assert_invalid(&valid_with(&json!({"houseSystem": "koch"})), "houseSystem").await;
    assert_invalid(&valid_with(&json!({"houseSystem": 1})), "houseSystem").await;
}

#[tokio::test]
async fn a_null_house_system_means_the_default() {
    let (status, _, chart) = post(&valid_with(&json!({"houseSystem": null}))).await;
    assert_eq!(status, StatusCode::OK, "{chart}");
    assert_eq!(chart["houseSystem"], "placidus");
}

#[tokio::test]
async fn missing_time_known_is_a_time_known_error() {
    assert_invalid(&valid_with(&json!({"timeKnown": null})), "timeKnown").await;
    assert_invalid(&valid_with(&json!({"timeKnown": "yes"})), "timeKnown").await;
    assert_invalid(&valid_with(&json!({"timeKnown": 1})), "timeKnown").await;
}

#[tokio::test]
async fn a_dst_gap_is_a_birth_error_with_the_gap_message() {
    let body = valid_with(&json!({
        "birth": "2021-03-14T02:30",
        "zone": "America/New_York",
        "lat": 40.7,
        "lon": -74.0,
    }));
    let problem = assert_invalid(&body, "birth").await;
    assert_eq!(
        problem["errors"][0]["message"],
        "does not exist in the zone (DST gap)"
    );
}

#[tokio::test]
async fn malformed_json_is_a_body_error() {
    for raw in ["{", "", "not json", "{\"birth\": }"] {
        let (status, ct, problem) = send(post_raw(raw)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{raw:?}");
        assert!(ct.starts_with("application/problem+json"), "{ct}");
        assert_eq!(problem["type"], "urn:problem:validation-error");
        assert_eq!(problem["errors"][0]["field"], "body", "{raw:?}");
    }
}

#[tokio::test]
async fn a_non_json_content_type_is_a_body_error() {
    let req = Request::post("/astro/chart")
        .header("content-type", "text/plain")
        .body(Body::from(valid_with(&json!({})).to_string()))
        .unwrap();
    let (status, _, problem) = send(req).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(problem["errors"][0]["field"], "body");
}

#[tokio::test]
async fn a_non_object_or_unknown_field_is_a_body_error() {
    for raw in ["[]", "null", "42", "\"x\""] {
        let (status, _, problem) = send(post_raw(raw)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{raw}");
        assert_eq!(problem["errors"][0]["field"], "body", "{raw}");
    }
    assert_invalid(&valid_with(&json!({"extra": 1})), "body").await;
}

#[tokio::test]
async fn every_problem_is_reported_not_just_the_first() {
    let body = json!({"birth": "x", "zone": "Mars/Base", "lat": "n", "timeKnown": true});
    let (_, _, problem) = post(&body).await;
    let fields: Vec<&str> = problem["errors"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["field"].as_str().unwrap())
        .collect();
    assert_eq!(fields, ["birth", "lat", "lon"]);
}

struct BrokenEphemeris;

impl EphemerisPort for BrokenEphemeris {
    fn apparent(&self, _: Planet, _: Moment) -> anyhow::Result<Ecliptic> {
        Err(anyhow::anyhow!("ephemeris broke"))
    }
}

#[tokio::test]
async fn an_ephemeris_failure_is_a_generic_500() {
    let req = post_raw(&valid_with(&json!({})).to_string());
    let (status, ct, problem) = send_to(AppState::with_ephemeris(BrokenEphemeris), req).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(problem["type"], "urn:problem:internal-error");
    assert_eq!(problem["detail"], "Unexpected error");
    assert!(problem["trace_id"].is_string(), "{problem}");
    assert!(
        !problem.to_string().contains("ephemeris broke"),
        "{problem}"
    );
}
