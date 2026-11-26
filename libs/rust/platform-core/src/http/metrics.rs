//! RED metrics for axum services: `http.server.request.duration` histogram.
//!
//! Apply with `Router::layer(http_metrics_layer())` next to [`trace_layer`](super::trace_layer),
//! so axum has already resolved `MatchedPath`. Label cardinality is bounded: the route is the
//! matched template (attribute absent when no route matched, e.g. 404 fallbacks), the method is
//! normalised to the standard set, and no ids or raw paths are ever recorded.

use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Instant;

use axum::extract::MatchedPath;
use http::{Request, Response};
use opentelemetry::metrics::{Histogram, Meter};
use opentelemetry::KeyValue;
use tower::{Layer, Service};

/// Explicit histogram bucket boundaries (seconds) shared by HTTP and consumer duration metrics.
pub const DURATION_BUCKETS_S: [f64; 14] = [
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0,
];

const METRIC_NAME: &str = "http.server.request.duration";

/// Tower layer recording [`METRIC_NAME`]; see the module docs.
#[derive(Clone, Debug)]
pub struct HttpMetricsLayer {
    duration: Histogram<f64>,
}

/// Layer using the global meter provider (`platform-core` meter). Call after `telemetry::init`.
pub fn http_metrics_layer() -> HttpMetricsLayer {
    http_metrics_layer_with_meter(&opentelemetry::global::meter("platform-core"))
}

/// Layer recording on the given meter (used by tests with an in-memory provider).
pub fn http_metrics_layer_with_meter(meter: &Meter) -> HttpMetricsLayer {
    let duration = meter
        .f64_histogram(METRIC_NAME)
        .with_unit("s")
        .with_description("Duration of HTTP server requests")
        .with_boundaries(DURATION_BUCKETS_S.to_vec())
        .build();
    HttpMetricsLayer { duration }
}

impl<S> Layer<S> for HttpMetricsLayer {
    type Service = HttpMetrics<S>;

    fn layer(&self, inner: S) -> Self::Service {
        HttpMetrics {
            inner,
            duration: self.duration.clone(),
        }
    }
}

#[derive(Clone, Debug)]
pub struct HttpMetrics<S> {
    inner: S,
    duration: Histogram<f64>,
}

/// Standard HTTP methods; anything else collapses to `_OTHER` (bounded cardinality).
fn normalize_method(method: &str) -> &'static str {
    match method {
        "GET" => "GET",
        "HEAD" => "HEAD",
        "POST" => "POST",
        "PUT" => "PUT",
        "DELETE" => "DELETE",
        "CONNECT" => "CONNECT",
        "OPTIONS" => "OPTIONS",
        "TRACE" => "TRACE",
        "PATCH" => "PATCH",
        _ => "_OTHER",
    }
}

impl<S, B, ResB> Service<Request<B>> for HttpMetrics<S>
where
    S: Service<Request<B>, Response = Response<ResB>>,
    S::Future: Send + 'static,
    S::Error: 'static,
    B: 'static,
    ResB: 'static,
{
    type Response = Response<ResB>;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<B>) -> Self::Future {
        let started = Instant::now();
        let method = normalize_method(req.method().as_str());
        let route = req
            .extensions()
            .get::<MatchedPath>()
            .map(|p| p.as_str().to_owned());
        let duration = self.duration.clone();
        let fut = self.inner.call(req);
        Box::pin(async move {
            let res = fut.await?;
            let mut attrs = Vec::with_capacity(3);
            attrs.push(KeyValue::new("http.request.method", method));
            attrs.push(KeyValue::new(
                "http.response.status_code",
                i64::from(res.status().as_u16()),
            ));
            if let Some(route) = route {
                attrs.push(KeyValue::new("http.route", route));
            }
            duration.record(started.elapsed().as_secs_f64(), &attrs);
            Ok(res)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::normalize_method;

    #[test]
    fn unknown_methods_collapse_to_other() {
        assert_eq!(normalize_method("GET"), "GET");
        assert_eq!(normalize_method("PATCH"), "PATCH");
        assert_eq!(normalize_method("BREW"), "_OTHER");
    }
}
