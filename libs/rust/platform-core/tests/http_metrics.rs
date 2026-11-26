//! RED metrics middleware: route templates only, bounded cardinality.

use axum::body::Body;
use axum::extract::Path;
use axum::routing::get;
use axum::Router;
use http::{Request, StatusCode};
use opentelemetry::KeyValue;
use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};
use opentelemetry_sdk::metrics::{InMemoryMetricExporter, PeriodicReader, SdkMeterProvider};
use platform_core::http::{http_metrics_layer_with_meter, ApiError, DURATION_BUCKETS_S};
use tower::ServiceExt as _;

const METRIC: &str = "http.server.request.duration";

struct Point {
    attrs: Vec<KeyValue>,
    count: u64,
    bounds: Vec<f64>,
}

impl Point {
    fn attr(&self, key: &str) -> Option<String> {
        self.attrs
            .iter()
            .find(|kv| kv.key.as_str() == key)
            .map(|kv| kv.value.to_string())
    }
}

struct Harness {
    provider: SdkMeterProvider,
    exporter: InMemoryMetricExporter,
    app: Router,
}

fn harness() -> Harness {
    let exporter = InMemoryMetricExporter::default();
    let provider = SdkMeterProvider::builder()
        .with_reader(PeriodicReader::builder(exporter.clone()).build())
        .build();
    let meter = opentelemetry::metrics::MeterProvider::meter(&provider, "test");
    let app = Router::new()
        .route("/items/{id}", get(|Path(_id): Path<String>| async { "ok" }))
        .route(
            "/boom",
            get(|| async { Err::<&str, _>(ApiError::Internal(anyhow::anyhow!("x"))) }),
        )
        .layer(http_metrics_layer_with_meter(&meter));
    Harness {
        provider,
        exporter,
        app,
    }
}

impl Harness {
    async fn get(&self, uri: &str) -> StatusCode {
        self.app
            .clone()
            .oneshot(Request::get(uri).body(Body::empty()).unwrap())
            .await
            .unwrap()
            .status()
    }

    fn points(&self) -> Vec<Point> {
        self.provider.force_flush().expect("flush");
        let mut out = Vec::new();
        for rm in self.exporter.get_finished_metrics().expect("metrics") {
            for sm in rm.scope_metrics() {
                for m in sm.metrics().filter(|m| m.name() == METRIC) {
                    assert_eq!(m.unit(), "s");
                    if let AggregatedMetrics::F64(MetricData::Histogram(h)) = m.data() {
                        for dp in h.data_points() {
                            out.push(Point {
                                attrs: dp.attributes().cloned().collect(),
                                count: dp.count(),
                                bounds: dp.bounds().collect(),
                            });
                        }
                    }
                }
            }
        }
        out
    }
}

#[tokio::test]
async fn templated_route_recorded() {
    let h = harness();
    assert_eq!(h.get("/items/42").await, StatusCode::OK);
    let points = h.points();
    let p = points
        .iter()
        .find(|p| p.attr("http.route").as_deref() == Some("/items/{id}"))
        .expect("point with templated route");
    assert_eq!(p.attr("http.request.method").as_deref(), Some("GET"));
    assert_eq!(p.attr("http.response.status_code").as_deref(), Some("200"));
    assert_eq!(p.count, 1);
    assert!(
        points
            .iter()
            .all(|p| p.attr("http.route").as_deref() != Some("/items/42")),
        "raw path must never be a label"
    );
}

#[tokio::test]
async fn unmatched_route_has_no_route_attribute() {
    let h = harness();
    assert_eq!(h.get("/nope/123").await, StatusCode::NOT_FOUND);
    let points = h.points();
    assert!(!points.is_empty());
    assert!(points.iter().all(|p| p.attr("http.route").is_none()));
    assert!(points
        .iter()
        .any(|p| p.attr("http.response.status_code").as_deref() == Some("404")));
}

#[tokio::test]
async fn server_error_status_recorded() {
    let h = harness();
    assert_eq!(h.get("/boom").await, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(h
        .points()
        .iter()
        .any(|p| p.attr("http.response.status_code").as_deref() == Some("500")));
}

#[tokio::test]
async fn histogram_uses_platform_buckets() {
    let h = harness();
    h.get("/items/1").await;
    let points = h.points();
    assert_eq!(points[0].bounds, DURATION_BUCKETS_S.to_vec());
}
