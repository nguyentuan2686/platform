//! HTTP adapter tests over the in-process router (`tower::ServiceExt::oneshot`).

use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt as _;
use serde_json::{json, Value};
use tower::ServiceExt as _;

// The service crate in a group of its own: rustfmt sorts within a group, so the order of these
// lines does not depend on the rendered crate name.
use __CRATE__::app::{router, AppState};
use __CRATE__::config::DEFAULT_SERVICE_NAME;

async fn send(req: Request<Body>) -> (StatusCode, String, Value) {
    let state = AppState::new(DEFAULT_SERVICE_NAME).unwrap();
    let res = router(state).oneshot(req).await.unwrap();
    let status = res.status();
    let ct = res
        .headers()
        .get("content-type")
        .map_or_else(String::new, |v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, ct, serde_json::from_slice(&bytes).unwrap())
}

async fn get(uri: &str) -> (StatusCode, String, Value) {
    send(Request::get(uri).body(Body::empty()).unwrap()).await
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
async fn info_names_the_service() {
    let (status, ct, body) = get("/v1/info").await;
    assert_eq!(status, StatusCode::OK);
    assert!(ct.starts_with("application/json"), "{ct}");
    assert_eq!(body, json!({"service": DEFAULT_SERVICE_NAME}));
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
    let req = Request::post("/v1/info").body(Body::empty()).unwrap();
    let (status, ct, body) = send(req).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(body["detail"], "route POST /v1/info not found");
    assert!(body["trace_id"].is_string(), "{body}");
}
