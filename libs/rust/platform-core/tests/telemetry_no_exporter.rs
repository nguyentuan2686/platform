//! Separate test binary: owns the process-global subscriber.
//! Without `OTEL_EXPORTER_OTLP_ENDPOINT` the inbound trace context must still be continued, so
//! logs carry the caller's `trace_id`.

use std::collections::HashMap;

use platform_core::telemetry::{current_trace_id, init};
use tracing_opentelemetry::OpenTelemetrySpanExt;

// NOTE: `init` installs the global subscriber, so this file must keep exactly ONE test.

#[test]
fn init_without_endpoint_still_continues_remote_trace() {
    std::env::remove_var("OTEL_EXPORTER_OTLP_ENDPOINT");
    let _guard = init("no-exporter").expect("init without OTLP endpoint");

    let remote_trace_id = "4bf92f3577b34da6a3ce929d0e0e4736";
    let carrier: HashMap<String, String> = HashMap::from([(
        "traceparent".to_owned(),
        format!("00-{remote_trace_id}-00f067aa0ba902b7-01"),
    )]);
    let parent = opentelemetry::global::get_text_map_propagator(|p| p.extract(&carrier));

    let span = tracing::info_span!("work");
    let _ = span.set_parent(parent);
    let _entered = span.enter();

    let id = current_trace_id();
    assert!(
        !id.is_empty(),
        "trace id must not be empty without an exporter"
    );
    assert_eq!(id, remote_trace_id);
}
