use std::sync::Arc;

use axum::body::Body;
use axum::routing::{get, post};
use axum::Router;
use http::{header, Request, StatusCode};
use http_body_util::BodyExt;
use opentelemetry::trace::TracerProvider as _;
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::{InMemorySpanExporter, SdkTracerProvider};
use platform_core::http::{
    health_router, trace_layer, ApiError, ApiJson, ApiPath, ApiQuery, FieldError, ReadinessCheck,
};
use platform_core::telemetry::current_trace_id;
use serde::Deserialize;
use serde_json::Value;
use tower::ServiceExt as _;
use tracing_subscriber::layer::SubscriberExt;

struct Fixed(bool);

#[async_trait::async_trait]
impl ReadinessCheck for Fixed {
    async fn ready(&self) -> bool {
        self.0
    }
}

#[derive(Deserialize)]
struct Body1 {
    #[allow(dead_code)]
    name: String,
}

#[derive(Deserialize)]
struct Q {
    #[allow(dead_code)]
    limit: Option<u32>,
}

fn app() -> Router {
    Router::new()
        .route(
            "/nf",
            get(|| async {
                Err::<(), _>(ApiError::NotFound {
                    resource: "Order",
                    id: "42".into(),
                })
            }),
        )
        .route(
            "/val",
            get(|| async {
                Err::<(), _>(ApiError::Validation(vec![FieldError {
                    field: "limit".into(),
                    message: "must be positive".into(),
                }]))
            }),
        )
        .route(
            "/bad",
            get(|| async { Err::<(), _>(ApiError::BadRequest("nope".into())) }),
        )
        .route(
            "/boom",
            get(|| async {
                Err::<(), _>(ApiError::Internal(anyhow::anyhow!("db password=hunter2")))
            }),
        )
        .route("/json", post(|ApiJson(_b): ApiJson<Body1>| async { "ok" }))
        .route("/q", get(|ApiQuery(_q): ApiQuery<Q>| async { "ok" }))
        .route(
            "/p/{id}",
            get(|ApiPath(id): ApiPath<u32>| async move { id.to_string() }),
        )
        .route("/trace", get(|| async { current_trace_id() }))
        .merge(health_router(Arc::new(Fixed(true))))
        .layer(trace_layer())
}

async fn call(app: Router, req: Request<Body>) -> (StatusCode, http::HeaderMap, Vec<u8>) {
    let res = app.oneshot(req).await.unwrap();
    let (parts, body) = res.into_parts();
    (
        parts.status,
        parts.headers,
        body.collect().await.unwrap().to_bytes().to_vec(),
    )
}

fn get_req(uri: &str) -> Request<Body> {
    Request::get(uri).body(Body::empty()).unwrap()
}

async fn problem(uri: &str, status: u16) -> Value {
    let (s, h, b) = call(app(), get_req(uri)).await;
    assert_eq!(s.as_u16(), status);
    assert_eq!(h[header::CONTENT_TYPE], "application/problem+json");
    serde_json::from_slice(&b).unwrap()
}

#[tokio::test]
async fn not_found_is_problem_json() {
    let v = problem("/nf", 404).await;
    assert_eq!(v["type"], "urn:problem:not-found");
    assert_eq!(v["title"], "Not Found");
    assert_eq!(v["status"], 404);
    assert_eq!(v["detail"], "Order 42 not found");
    assert!(v.as_object().unwrap().contains_key("trace_id"));
    assert!(!v.as_object().unwrap().contains_key("errors"));
}

#[tokio::test]
async fn validation_has_errors_list() {
    let v = problem("/val", 400).await;
    assert_eq!(v["type"], "urn:problem:validation-error");
    assert_eq!(v["title"], "Bad Request");
    assert_eq!(v["errors"][0]["field"], "limit");
    assert_eq!(v["errors"][0]["message"], "must be positive");
}

#[tokio::test]
async fn bad_request_problem() {
    let v = problem("/bad", 400).await;
    assert_eq!(v["type"], "urn:problem:validation-error");
    assert_eq!(v["detail"], "nope");
}

#[tokio::test]
async fn internal_hides_inner_error() {
    let (s, _, b) = call(app(), get_req("/boom")).await;
    assert_eq!(s, StatusCode::INTERNAL_SERVER_ERROR);
    let text = String::from_utf8(b).unwrap();
    assert!(!text.contains("hunter2"));
    let v: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(v["type"], "urn:problem:internal-error");
    assert_eq!(v["title"], "Internal Server Error");
    assert_eq!(v["detail"], "Unexpected error");
}

#[tokio::test]
async fn malformed_json_is_problem_400() {
    let req = Request::post("/json")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from("{not json"))
        .unwrap();
    let (s, h, b) = call(app(), req).await;
    assert_eq!(s, StatusCode::BAD_REQUEST);
    assert_eq!(h[header::CONTENT_TYPE], "application/problem+json");
    let v: Value = serde_json::from_slice(&b).unwrap();
    assert_eq!(v["type"], "urn:problem:validation-error");
}

#[tokio::test]
async fn missing_json_field_is_problem_400() {
    let req = Request::post("/json")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (s, h, _) = call(app(), req).await;
    assert_eq!(s, StatusCode::BAD_REQUEST);
    assert_eq!(h[header::CONTENT_TYPE], "application/problem+json");
}

#[tokio::test]
async fn bad_query_is_problem_400_naming_the_field() {
    let v = problem("/q?limit=abc", 400).await;
    assert_eq!(v["type"], "urn:problem:validation-error");
    assert_eq!(v["errors"][0]["field"], "limit");
    let (s, ..) = call(app(), get_req("/q?limit=5")).await;
    assert_eq!(s, StatusCode::OK);
}

#[tokio::test]
async fn bad_path_is_problem_400() {
    let v = problem("/p/xyz", 400).await;
    assert_eq!(v["type"], "urn:problem:validation-error");
}

#[tokio::test]
async fn live_is_up() {
    let (s, _, b) = call(app(), get_req("/health/live")).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(serde_json::from_slice::<Value>(&b).unwrap()["status"], "UP");
}

#[tokio::test]
async fn ready_follows_check() {
    for (ok, code, status) in [(true, 200, "UP"), (false, 503, "DOWN")] {
        let app = health_router(Arc::new(Fixed(ok)));
        let (s, _, b) = call(app, get_req("/health/ready")).await;
        assert_eq!(s.as_u16(), code);
        assert_eq!(
            serde_json::from_slice::<Value>(&b).unwrap()["status"],
            status
        );
    }
}

#[tokio::test]
async fn traceparent_header_sets_handler_trace_id_and_span_attrs() {
    opentelemetry::global::set_text_map_propagator(TraceContextPropagator::new());
    let exporter = InMemorySpanExporter::default();
    let provider = SdkTracerProvider::builder()
        .with_simple_exporter(exporter.clone())
        .build();
    let subscriber = tracing_subscriber::registry()
        .with(tracing_opentelemetry::layer().with_tracer(provider.tracer("test")));
    let _guard = tracing::subscriber::set_default(subscriber);

    let trace_id = "4bf92f3577b34da6a3ce929d0e0e4736";
    let req = Request::get("/trace")
        .header("traceparent", format!("00-{trace_id}-00f067aa0ba902b7-01"))
        .body(Body::empty())
        .unwrap();
    let (s, _, b) = call(app(), req).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(String::from_utf8(b).unwrap(), trace_id);

    let (_, _, b) = call(app(), get_req("/nope")).await;
    drop(b);
    let spans = exporter.get_finished_spans().unwrap();
    let routed = spans
        .iter()
        .find(|s| s.name == "GET /trace")
        .expect("span named by route");
    let attr = |k: &str| {
        routed
            .attributes
            .iter()
            .find(|kv| kv.key.as_str() == k)
            .map(|kv| kv.value.to_string())
    };
    assert_eq!(attr("http.route").as_deref(), Some("/trace"));
    assert_eq!(attr("http.request.method").as_deref(), Some("GET"));
    assert_eq!(attr("http.response.status_code").as_deref(), Some("200"));
    assert!(spans.iter().any(|s| s.name == "GET unmatched"));
}

struct Hang;

#[async_trait::async_trait]
impl ReadinessCheck for Hang {
    async fn ready(&self) -> bool {
        std::future::pending().await
    }
}

#[tokio::test]
async fn hanging_readiness_check_times_out_as_down() {
    let app = platform_core::http::health_router_with_timeout(
        Arc::new(Hang),
        std::time::Duration::from_millis(50),
    );
    let (s, _, b) = call(app, get_req("/health/ready")).await;
    assert_eq!(s, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        serde_json::from_slice::<Value>(&b).unwrap()["status"],
        "DOWN"
    );
}
