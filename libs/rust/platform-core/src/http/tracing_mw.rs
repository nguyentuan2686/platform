//! Trace propagation for axum: continues the inbound W3C trace and names request spans by route.
//!
//! Apply with `Router::layer(trace_layer())` (not around the finished `Router`), so that axum has
//! already resolved `MatchedPath` when the span is created.

use axum::extract::MatchedPath;
use http::{Request, Response};
use opentelemetry_http::HeaderExtractor;
use tower_http::classify::{ServerErrorsAsFailures, SharedClassifier};
use tower_http::trace::{MakeSpan, OnResponse, TraceLayer};
use tracing::Span;
use tracing_opentelemetry::OpenTelemetrySpanExt;

/// Builds the request span: parent = remote context from `traceparent`, name `"<METHOD> <route>"`
/// (`"<METHOD> unmatched"` when no route matched, to keep span-name cardinality bounded).
#[derive(Clone, Copy, Debug, Default)]
pub struct PlatformMakeSpan;

impl<B> MakeSpan<B> for PlatformMakeSpan {
    fn make_span(&mut self, req: &Request<B>) -> Span {
        let method = req.method().as_str();
        let route = req
            .extensions()
            .get::<MatchedPath>()
            .map(MatchedPath::as_str);
        let name = format!("{method} {}", route.unwrap_or("unmatched"));
        let span = tracing::info_span!(
            "http.request",
            otel.name = %name,
            otel.kind = "server",
            http.request.method = %method,
            http.route = route.unwrap_or(""),
            http.response.status_code = tracing::field::Empty,
        );
        let parent = opentelemetry::global::get_text_map_propagator(|p| {
            p.extract(&HeaderExtractor(req.headers()))
        });
        // Fails only when the span is disabled by the active subscriber's filter; nothing to do.
        let _ = span.set_parent(parent);
        span
    }
}

/// Records the response status on the request span.
#[derive(Clone, Copy, Debug, Default)]
pub struct PlatformOnResponse;

impl<B> OnResponse<B> for PlatformOnResponse {
    fn on_response(self, res: &Response<B>, _latency: std::time::Duration, span: &Span) {
        span.record("http.response.status_code", res.status().as_u16());
    }
}

pub type PlatformTraceLayer =
    TraceLayer<SharedClassifier<ServerErrorsAsFailures>, PlatformMakeSpan, (), PlatformOnResponse>;

/// Tower layer creating one span per request; see the module docs.
pub fn trace_layer() -> PlatformTraceLayer {
    TraceLayer::new_for_http()
        .make_span_with(PlatformMakeSpan)
        .on_request(())
        .on_response(PlatformOnResponse)
}
