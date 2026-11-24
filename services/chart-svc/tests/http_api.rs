//! HTTP adapter tests over the in-process router (`tower::ServiceExt::oneshot`).

use axum::body::Body;
use axum::http::{Request, StatusCode};
use chart_svc::app::{router, AppState};
use http_body_util::BodyExt as _;
use serde_json::Value;
use tower::ServiceExt as _;

async fn get(uri: &str) -> (StatusCode, String, Value) {
    let res = router(AppState::new())
        .oneshot(Request::get(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = res.status();
    let ct = res
        .headers()
        .get("content-type")
        .map_or_default(|v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, ct, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn live_is_up() {
    let (status, _, body) = get("/health/live").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, serde_json::json!({"status": "UP"}));
}

#[tokio::test]
async fn ready_is_up() {
    let (status, _, body) = get("/health/ready").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "UP");
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
