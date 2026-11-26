//! Separate test binary (ONE test): owns the global subscriber. With an unreachable collector and
//! several export intervals, the exporter's failure events must not spam stdout: at most one
//! error-level line, and its message must not be empty.

use std::io::Write;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use opentelemetry_sdk::metrics::{PeriodicReader, SdkMeterProvider};
use platform_core::telemetry::{env_filter, JsonLayer};
use tracing_subscriber::fmt::MakeWriter;
use tracing_subscriber::layer::SubscriberExt;

#[derive(Clone, Default)]
struct Buf(Arc<Mutex<Vec<u8>>>);

impl Write for Buf {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(data);
        Ok(data.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl MakeWriter<'_> for Buf {
    type Writer = Buf;
    fn make_writer(&self) -> Buf {
        self.clone()
    }
}

#[test]
fn unreachable_collector_logs_at_most_one_export_error() {
    std::env::remove_var("RUST_LOG");
    std::env::set_var("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9");
    let buf = Buf::default();
    let subscriber = tracing_subscriber::registry()
        .with(env_filter())
        .with(JsonLayer::new("t", buf.clone()).with_sdk_throttle(Duration::from_secs(300)));
    tracing::subscriber::set_global_default(subscriber).expect("only test in this binary");

    let exporter = opentelemetry_otlp::MetricExporter::builder()
        .with_http()
        .build()
        .expect("exporter");
    let provider = SdkMeterProvider::builder()
        .with_reader(
            PeriodicReader::builder(exporter)
                .with_interval(Duration::from_millis(150))
                .build(),
        )
        .build();
    let hist = opentelemetry::metrics::MeterProvider::meter(&provider, "t")
        .f64_histogram("x")
        .build();

    // Wait (deadline) until the first failure is logged, then let several more intervals pass.
    let text = |b: &Buf| String::from_utf8(b.0.lock().unwrap().clone()).unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    while !text(&buf).contains("\"level\":\"ERROR\"") {
        hist.record(0.1, &[]);
        assert!(
            Instant::now() < deadline,
            "no export error seen: {}",
            text(&buf)
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    let seen = Instant::now();
    while seen.elapsed() < Duration::from_millis(1500) {
        hist.record(0.1, &[]);
        std::thread::sleep(Duration::from_millis(50));
    }
    let out = text(&buf);
    let errors: Vec<&str> = out
        .lines()
        .filter(|l| l.contains("\"level\":\"ERROR\""))
        .collect();
    assert_eq!(errors.len(), 1, "export errors must be collapsed: {out}");
    let v: serde_json::Value = serde_json::from_str(errors[0]).unwrap();
    assert!(!v["message"].as_str().unwrap().is_empty(), "{v}");
    assert!(
        !out.contains("Global meter provider"),
        "INFO chatter of the SDK is filtered by default"
    );
    drop(provider);
}
