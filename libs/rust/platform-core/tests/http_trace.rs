//! Separate test binary with exactly ONE test: owns the process-global subscriber.
//!
//! `tracing` caches callsite interest process-wide. With a thread-local `set_default` subscriber
//! in a binary whose other tests run in parallel, the request-span callsite could be cached as
//! "never" (even after `rebuild_interest_cache`), so the handler saw no trace context and the
//! test flaked in CI. A global subscriber installed before any span callsite is hit, in a binary
//! with no other tests, removes the race.

use axum::body::Body;
use axum::routing::get;
use axum::Router;
use http::{Request, StatusCode};
use http_body_util::BodyExt;
use opentelemetry::trace::TracerProvider as _;
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::{InMemorySpanExporter, SdkTracerProvider};
use platform_core::http::trace_layer;
use platform_core::telemetry::current_trace_id;
use tower::ServiceExt as _;
use tracing_subscriber::layer::SubscriberExt;

fn app() -> Router {
    Router::new()
        .route("/trace", get(|| async { current_trace_id() }))
        .layer(trace_layer())
}

async fn get_body(req: Request<Body>) -> (StatusCode, Vec<u8>) {
    let res = app().oneshot(req).await.unwrap();
    let (parts, body) = res.into_parts();
    (
        parts.status,
        body.collect().await.unwrap().to_bytes().to_vec(),
    )
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
    tracing::subscriber::set_global_default(subscriber).expect("only test in this binary");

    let trace_id = "4bf92f3577b34da6a3ce929d0e0e4736";
    let req = Request::get("/trace")
        .header("traceparent", format!("00-{trace_id}-00f067aa0ba902b7-01"))
        .body(Body::empty())
        .unwrap();
    let (s, b) = get_body(req).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(String::from_utf8(b).unwrap(), trace_id);

    let (_, b) = get_body(Request::get("/nope").body(Body::empty()).unwrap()).await;
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
