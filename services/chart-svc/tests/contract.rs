//! Contract tests: real HTTP responses (and the request examples) against
//! `contracts/openapi/chart-svc.yaml`, the contract the reading service builds on.

use std::path::PathBuf;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use chart_svc::app::{router, AppState};
use chart_svc::application::birth::NormalizedBirth;
use chart_svc::application::ports::ZiweiEngine;
use chart_svc::domain::chart::{Chart, Flow, Gender};
use http_body_util::BodyExt as _;
use jsonschema::Validator;
use serde_json::{json, Value};
use tower::ServiceExt as _;

fn openapi() -> Value {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../contracts/openapi/chart-svc.yaml");
    yaml_serde::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// Validator for the schema at JSON pointer `pointer` inside the `OpenAPI` document. The whole
/// document is the root resource so `$ref: '#/components/...'` resolves.
fn validator_in(mut doc: Value, pointer: &str) -> Validator {
    assert!(doc.pointer(pointer).is_some(), "no schema at {pointer}");
    doc.as_object_mut()
        .unwrap()
        .insert("$ref".into(), Value::String(format!("#{pointer}")));
    jsonschema::options()
        .should_validate_formats(true)
        .build(&doc)
        .unwrap()
}

fn validator(pointer: &str) -> Validator {
    validator_in(openapi(), pointer)
}

fn assert_valid(v: &Validator, instance: &Value) {
    let errors: Vec<String> = v.iter_errors(instance).map(|e| e.to_string()).collect();
    assert!(errors.is_empty(), "{instance} violates schema: {errors:?}");
}

const CHART_REQ: &str = "/paths/~1tuvi~1chart/post/requestBody/content/application~1json/schema";
const CHART_200: &str = "/paths/~1tuvi~1chart/post/responses/200/content/application~1json/schema";
const CHART_400: &str =
    "/paths/~1tuvi~1chart/post/responses/400/content/application~1problem+json/schema";
const CHART_500: &str =
    "/paths/~1tuvi~1chart/post/responses/500/content/application~1problem+json/schema";
const DAILY_REQ: &str = "/paths/~1tuvi~1daily/post/requestBody/content/application~1json/schema";
const DAILY_200: &str = "/paths/~1tuvi~1daily/post/responses/200/content/application~1json/schema";
const DAILY_400: &str =
    "/paths/~1tuvi~1daily/post/responses/400/content/application~1problem+json/schema";

async fn post(state: AppState, uri: &str, body: &Value) -> (StatusCode, Value) {
    let res = router(state)
        .oneshot(
            Request::post(uri)
                .header("content-type", "application/json")
                .body(Body::from(serde_json::to_vec(body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

fn base() -> Value {
    json!({"birth": "1994-03-12T08:30", "zone": "Asia/Ho_Chi_Minh", "gender": "male", "calendar": "VN"})
}

fn daily_body() -> Value {
    let mut b = base();
    b["date"] = json!("2026-11-25");
    b
}

#[tokio::test]
async fn chart_response_matches_openapi_for_both_calendars() {
    assert_valid(&validator(CHART_REQ), &base());
    for calendar in ["VN", "CN"] {
        for gender in ["male", "female"] {
            let mut body = base();
            body["calendar"] = json!(calendar);
            body["gender"] = json!(gender);
            let (status, chart) = post(AppState::new(), "/tuvi/chart", &body).await;
            assert_eq!(status, StatusCode::OK);
            assert_valid(&validator(CHART_200), &chart);
        }
    }
}

#[tokio::test]
async fn daily_response_matches_openapi() {
    assert_valid(&validator(DAILY_REQ), &daily_body());
    for date in ["1900-01-01", "2026-11-25", "2100-12-31"] {
        let mut body = daily_body();
        body["date"] = json!(date);
        let (status, daily) = post(AppState::new(), "/tuvi/daily", &body).await;
        assert_eq!(status, StatusCode::OK, "{date}");
        assert_valid(&validator(DAILY_200), &daily);
    }
}

#[tokio::test]
async fn problems_match_openapi() {
    let mut bad = base();
    bad["gender"] = json!("x");
    let (status, p) = post(AppState::new(), "/tuvi/chart", &bad).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_valid(&validator(CHART_400), &p);
    let (status, p) = post(AppState::new(), "/tuvi/daily", &base()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_valid(&validator(DAILY_400), &p);
}

struct BrokenEngine;

impl ZiweiEngine for BrokenEngine {
    fn chart(&self, _: &NormalizedBirth, _: Gender) -> anyhow::Result<Chart> {
        Err(anyhow::anyhow!("boom"))
    }

    fn flow(&self, _: &NormalizedBirth, _: Gender, _: time::Date) -> anyhow::Result<Flow> {
        Err(anyhow::anyhow!("boom"))
    }
}

#[tokio::test]
async fn internal_error_matches_openapi() {
    let (status, p) = post(AppState::with_engine(BrokenEngine), "/tuvi/chart", &base()).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_valid(&validator(CHART_500), &p);
}

#[test]
fn request_schemas_reject_bad_requests() {
    let chart = validator(CHART_REQ);
    for bad in [
        json!({"gender": "male"}),
        json!({"birth": "1994-03-12T08:30"}),
        json!({"birth": "1994-03-12T08:30+07:00", "gender": "male"}),
        json!({"birth": "1994-03-12T08:30", "gender": "other"}),
        json!({"birth": "1994-03-12T08:30", "gender": "male", "calendar": "JP"}),
    ] {
        assert!(!chart.is_valid(&bad), "{bad}");
    }
    let daily = validator(DAILY_REQ);
    assert!(!daily.is_valid(&base()), "date is required for daily");
    let mut extra = base();
    extra["timezone"] = json!("America/New_York");
    assert!(!chart.is_valid(&extra), "unknown field on chart");
    let mut extra = daily_body();
    extra["timezone"] = json!("America/New_York");
    assert!(!daily.is_valid(&extra), "unknown field on daily");
    assert!(!chart.is_valid(&daily_body()), "date is not a chart field");
    let mut bad = daily_body();
    bad["gender"] = json!("other");
    assert!(
        !daily.is_valid(&bad),
        "daily fields resolve to the chart field schemas"
    );
}

#[tokio::test]
async fn openapi_schemas_reject_wrong_shapes() {
    // Guards against a validator that accepts everything (e.g. an unresolved $ref).
    let (_, chart) = post(AppState::new(), "/tuvi/chart", &base()).await;
    let chart_v = validator(CHART_200);
    assert!(!chart_v.is_valid(&json!({})));
    assert!(!chart_v.is_valid(&json!([])));

    let mut broken = chart.clone();
    broken["palaces"].as_array_mut().unwrap().pop();
    assert!(!chart_v.is_valid(&broken), "11 palaces");
    let mut broken = chart.clone();
    broken["hourBranch"] = json!("Dragon");
    assert!(!chart_v.is_valid(&broken), "unknown branch");
    let mut broken = chart.clone();
    broken["palaces"][0]["major"] = json!([{"key": "TuVi"}]);
    assert!(!chart_v.is_valid(&broken), "star without nameVi");
    let mut broken = chart;
    broken["extra"] = json!(1);
    assert!(!chart_v.is_valid(&broken), "additional property");

    assert!(!validator(DAILY_200).is_valid(&json!({"date": "2026-11-25"})));
    assert!(!validator(CHART_400).is_valid(&json!({"title": "Bad Request"})));
}

#[tokio::test]
async fn validator_rejects_a_broken_document() {
    // The same checks against a document whose chart schema was gutted must fail, proving the
    // validator really follows the document under test.
    let (_, chart) = post(AppState::new(), "/tuvi/chart", &base()).await;
    let mut doc = openapi();
    doc["components"]["schemas"]["Chart"]["required"] = json!(["nonexistentField"]);
    let v = validator_in(doc, CHART_200);
    assert!(!v.is_valid(&chart));
}
