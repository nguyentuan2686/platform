use opentelemetry::trace::TracerProvider as _;
use opentelemetry_sdk::trace::{InMemorySpanExporter, SdkTracerProvider};
use platform_core::telemetry::{current_trace_id, init};
use tracing_subscriber::layer::SubscriberExt;

#[test]
fn current_trace_id_is_32_hex_inside_span_and_empty_outside() {
    let provider = SdkTracerProvider::builder()
        .with_simple_exporter(InMemorySpanExporter::default())
        .build();
    let subscriber = tracing_subscriber::registry()
        .with(tracing_opentelemetry::layer().with_tracer(provider.tracer("test")));
    tracing::subscriber::with_default(subscriber, || {
        assert_eq!(current_trace_id(), "");
        let span = tracing::info_span!("work");
        let _guard = span.enter();
        let id = current_trace_id();
        assert_eq!(id.len(), 32);
        assert!(id.chars().all(|c| c.is_ascii_hexdigit()));
    });
}

#[test]
fn init_twice_returns_error_instead_of_panicking() {
    let first = init("test-svc");
    assert!(first.is_ok());
    assert!(init("test-svc").is_err());
}
