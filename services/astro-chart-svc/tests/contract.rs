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
use astro_chart_svc::config::DEFAULT_SERVICE_NAME;

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

const INFO_200: &str = "/paths/~1v1~1info/get/responses/200/content/application~1json/schema";
const INFO_404: &str =
    "/paths/~1v1~1info/get/responses/404/content/application~1problem+json/schema";

async fn get(uri: &str) -> (StatusCode, Value) {
    let state = AppState::new(DEFAULT_SERVICE_NAME).unwrap();
    let res = router(state)
        .oneshot(Request::get(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = res.status();
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
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
        ["GET /health/live", "GET /health/ready", "GET /v1/info"]
    );
    for path in paths.keys() {
        let (status, _) = get(path).await;
        assert_eq!(status, StatusCode::OK, "{path}");
    }
}

#[tokio::test]
async fn info_matches_the_spec() {
    let (status, body) = get("/v1/info").await;
    assert_eq!(status, StatusCode::OK);
    assert_valid(&validator(INFO_200), &body);
}

#[test]
fn info_schema_is_not_vacuous() {
    assert!(!validator(INFO_200).is_valid(&json!({"service": ""})));
}

#[tokio::test]
async fn not_found_problem_matches_the_spec() {
    let (status, body) = get("/v1/nope").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_valid(&validator(INFO_404), &body);
    assert!(!validator(INFO_404).is_valid(&json!({"title": "x", "status": 404})));
}
