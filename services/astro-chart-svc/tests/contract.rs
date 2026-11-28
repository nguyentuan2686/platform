//! Contract tests: real HTTP responses against `contracts/openapi/astro-chart-svc.yaml`.

use std::path::PathBuf;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt as _;
use jsonschema::Validator;
use serde_json::{json, Value};
use tower::ServiceExt as _;

// The service crate in a group of its own: rustfmt sorts within a group, so the order of these
// lines does not depend on the rendered crate name.
use astro_chart_svc::app::{router, AppState};
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::config::DEFAULT_SERVICE_NAME;
use astro_chart_svc::domain::body::{Body as Planet, Ecliptic};
use astro_chart_svc::domain::time::Moment;

fn openapi() -> Value {
    // No literal service name on this line: rustfmt's wrapping must not depend on its length.
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../contracts/openapi")
        .join(format!("{DEFAULT_SERVICE_NAME}.yaml"));
    yaml_serde::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// Validator for the schema at JSON pointer `pointer` inside the `OpenAPI` document. The whole
/// document is the root resource so `$ref: '#/components/...'` resolves.
fn validator(pointer: &str) -> Validator {
    let mut doc = openapi();
    assert!(doc.pointer(pointer).is_some(), "no schema at {pointer}");
    doc.as_object_mut()
        .unwrap()
        .insert("$ref".into(), Value::String(format!("#{pointer}")));
    jsonschema::options().build(&doc).unwrap()
}

fn assert_valid(v: &Validator, instance: &Value) {
    let errors: Vec<String> = v.iter_errors(instance).map(|e| e.to_string()).collect();
    assert!(errors.is_empty(), "{instance} violates schema: {errors:?}");
}

const CHART_REQUEST: &str =
    "/paths/~1astro~1chart/post/requestBody/content/application~1json/schema";
const CHART_200: &str = "/paths/~1astro~1chart/post/responses/200/content/application~1json/schema";
const CHART_400: &str =
    "/paths/~1astro~1chart/post/responses/400/content/application~1problem+json/schema";
const CHART_500: &str =
    "/paths/~1astro~1chart/post/responses/500/content/application~1problem+json/schema";
const PROBLEM: &str = "/components/schemas/Problem";

async fn call(state: AppState, req: Request<Body>) -> (StatusCode, Value) {
    let res = router(state).oneshot(req).await.unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

async fn post_raw(state: AppState, raw: String) -> (StatusCode, Value) {
    call(
        state,
        Request::post("/astro/chart")
            .header("content-type", "application/json")
            .body(Body::from(raw))
            .unwrap(),
    )
    .await
}

async fn post_chart(state: AppState, body: &Value) -> (StatusCode, Value) {
    post_raw(state, body.to_string()).await
}

/// A valid request with `patch` merged over it (a `null` value removes the field).
fn request(patch: &Value) -> Value {
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

#[tokio::test]
async fn every_path_in_the_spec_is_served() {
    let doc = openapi();
    let paths = doc["paths"].as_object().unwrap();
    let mut declared: Vec<String> = Vec::new();
    for (path, ops) in paths {
        for method in ops.as_object().unwrap().keys() {
            declared.push(format!("{} {path}", method.to_uppercase()));
        }
    }
    declared.sort();
    assert_eq!(
        declared,
        ["GET /health/live", "GET /health/ready", "POST /astro/chart"]
    );
    for path in ["/health/live", "/health/ready"] {
        let (status, _) = call(
            AppState::new(),
            Request::get(path).body(Body::empty()).unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{path}");
    }
    let (status, _) = post_chart(AppState::new(), &request(&json!({}))).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn known_time_chart_matches_the_spec() {
    let (status, body) = post_chart(AppState::new(), &request(&json!({}))).await;
    assert_eq!(status, StatusCode::OK);
    assert_valid(&validator(CHART_200), &body);
}

#[tokio::test]
async fn unknown_time_chart_matches_the_spec() {
    let (status, body) = post_chart(AppState::new(), &request(&json!({"timeKnown": false}))).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.get("angles").is_none());
    assert_valid(&validator(CHART_200), &body);
}

#[tokio::test]
async fn house_fallback_chart_matches_the_spec() {
    let patch = json!({"zone": "Europe/Oslo", "lat": 69.65, "lon": 18.96});
    let (status, body) = post_chart(AppState::new(), &request(&patch)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["houseFallback"], "whole-sign");
    assert_valid(&validator(CHART_200), &body);
}

#[tokio::test]
async fn every_house_system_matches_the_spec() {
    for system in ["placidus", "whole-sign", "equal"] {
        let req = request(&json!({ "houseSystem": system }));
        assert_valid(&validator(CHART_REQUEST), &req);
        let (status, body) = post_chart(AppState::new(), &req).await;
        assert_eq!(status, StatusCode::OK);
        assert_valid(&validator(CHART_200), &body);
    }
}

#[test]
fn request_schema_requires_the_fields_and_closes_the_object() {
    let v = validator(CHART_REQUEST);
    assert!(v.is_valid(&request(&json!({}))));
    for required in ["birth", "zone", "lat", "lon", "timeKnown"] {
        assert!(
            !v.is_valid(&request(&json!({ required: null }))),
            "{required}"
        );
    }
    assert!(!v.is_valid(&request(&json!({"extra": 1}))));
    assert!(!v.is_valid(&request(&json!({"houseSystem": "koch"}))));
    assert!(!v.is_valid(&request(&json!({"lat": 91}))));
    assert!(!v.is_valid(&request(&json!({"lon": -181}))));
}

#[tokio::test]
async fn chart_schema_is_not_vacuous() {
    let (_, chart) = post_chart(AppState::new(), &request(&json!({}))).await;
    let v = validator(CHART_200);
    assert_valid(&v, &chart);

    let mut extra = chart.clone();
    extra["surprise"] = json!(1);
    assert!(!v.is_valid(&extra), "top level is closed");

    let mut extra = chart.clone();
    extra["bodies"][0]["label"] = json!("Mặt Trời");
    assert!(!v.is_valid(&extra), "bodies are closed");

    let mut extra = chart.clone();
    extra["angles"]["asc"]["x"] = json!(1);
    assert!(!v.is_valid(&extra), "angles are closed");

    let mut extra = chart.clone();
    extra["houses"][0]["x"] = json!(1);
    assert!(!v.is_valid(&extra), "houses are closed");

    let mut extra = chart.clone();
    extra["aspects"][0]["x"] = json!(1);
    assert!(!v.is_valid(&extra), "aspects are closed");

    let mut bad = chart.clone();
    bad["bodies"][0]["key"] = json!("Vulcan");
    assert!(!v.is_valid(&bad), "body keys are an enum");

    let mut bad = chart.clone();
    bad["bodies"][0]["sign"] = json!("Aries ");
    assert!(!v.is_valid(&bad), "signs are an enum");

    let mut bad = chart.clone();
    bad["aspects"][0]["type"] = json!("quincunx");
    assert!(!v.is_valid(&bad), "aspect types are an enum");

    let mut bad = chart.clone();
    bad.as_object_mut().unwrap().remove("moonSignUncertain");
    assert!(!v.is_valid(&bad), "moonSignUncertain is required");
}

#[tokio::test]
async fn validation_problems_match_the_spec_for_every_field() {
    let cases = [
        (json!({"birth": "1990-02-30T10:00"}), "birth"),
        (json!({"birth": "1899-12-31T12:00"}), "birth"),
        (json!({"zone": "Mars/Base"}), "zone"),
        (json!({"lat": 91}), "lat"),
        (json!({"lon": -181}), "lon"),
        (json!({"timeKnown": null}), "timeKnown"),
        (json!({"houseSystem": "koch"}), "houseSystem"),
        (json!({"extra": 1}), "body"),
    ];
    let v = validator(CHART_400);
    for (patch, field) in cases {
        let (status, body) = post_chart(AppState::new(), &request(&patch)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{patch}");
        assert_eq!(body["errors"][0]["field"], field, "{patch}");
        assert_valid(&v, &body);
    }
    let (status, body) = post_raw(AppState::new(), "{".to_owned()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["errors"][0]["field"], "body");
    assert_valid(&v, &body);
}

#[test]
fn the_400_field_enum_is_exactly_the_documented_one() {
    let v = validator(CHART_400);
    let problem = |field: &str| {
        json!({
            "type": "urn:problem:validation-error",
            "title": "Bad Request",
            "status": 400,
            "trace_id": "t",
            "errors": [{"field": field, "message": "m"}],
        })
    };
    for field in [
        "body",
        "birth",
        "zone",
        "lat",
        "lon",
        "timeKnown",
        "houseSystem",
    ] {
        assert!(v.is_valid(&problem(field)), "{field}");
    }
    assert!(!v.is_valid(&problem("gender")));
}

struct BrokenEphemeris;

impl EphemerisPort for BrokenEphemeris {
    fn apparent(&self, _: Planet, _: Moment) -> anyhow::Result<Ecliptic> {
        Err(anyhow::anyhow!("ephemeris broke"))
    }
}

#[tokio::test]
async fn internal_error_problem_matches_the_spec() {
    let (status, body) = post_chart(
        AppState::with_ephemeris(BrokenEphemeris),
        &request(&json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(body["detail"], "Unexpected error");
    assert_valid(&validator(CHART_500), &body);
}

#[tokio::test]
async fn not_found_problem_matches_the_spec() {
    let (status, body) = call(
        AppState::new(),
        Request::get("/v1/nope").body(Body::empty()).unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_valid(&validator(PROBLEM), &body);
    assert!(!validator(PROBLEM).is_valid(&json!({"title": "x", "status": 404})));
}
