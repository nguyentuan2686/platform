//! Telemetry: JSON logs on stdout and optional OTLP (http/protobuf) trace export.

pub mod json_layer;

use anyhow::Context as _;
use opentelemetry::trace::{TraceContextExt, TracerProvider as _};
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::SdkTracerProvider;
use tracing_opentelemetry::OpenTelemetrySpanExt;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::EnvFilter;

pub use json_layer::JsonLayer;

/// Flushes and shuts down the tracer provider (if any) on drop.
pub struct TelemetryGuard {
    provider: Option<SdkTracerProvider>,
}

impl Drop for TelemetryGuard {
    fn drop(&mut self) {
        if let Some(provider) = self.provider.take() {
            let _ = provider.shutdown();
        }
    }
}

/// Installs the global tracing subscriber (JSON layer on stdout, `RUST_LOG` filter defaulting to
/// `info`, an `OTel` tracer provider always, the OTLP exporter only when
/// `OTEL_EXPORTER_OTLP_ENDPOINT` is set) and the W3C propagator.
///
/// Returns an error (never panics) if a global subscriber is already installed.
pub fn init(service_name: &str) -> anyhow::Result<TelemetryGuard> {
    opentelemetry::global::set_text_map_propagator(TraceContextPropagator::new());

    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let json = JsonLayer::new(service_name, std::io::stdout);

    // The tracer provider and OTel layer are always installed so an inbound `traceparent` is
    // continued (and `trace_id`/`span_id` reach the logs) even without an exporter; spans are
    // only exported when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.
    let resource = opentelemetry_sdk::Resource::builder()
        .with_service_name(service_name.to_owned())
        .build();
    let mut builder = SdkTracerProvider::builder().with_resource(resource);
    if std::env::var("OTEL_EXPORTER_OTLP_ENDPOINT").is_ok_and(|v| !v.is_empty()) {
        let exporter = opentelemetry_otlp::SpanExporter::builder()
            .with_http()
            .build()
            .context("building OTLP span exporter")?;
        builder = builder.with_batch_exporter(exporter);
    }
    let provider = builder.build();
    let otel_layer =
        tracing_opentelemetry::layer().with_tracer(provider.tracer(service_name.to_owned()));

    tracing_subscriber::registry()
        .with(filter)
        .with(otel_layer)
        .with(json)
        .try_init()
        .context("a global tracing subscriber is already installed")?;

    Ok(TelemetryGuard {
        provider: Some(provider),
    })
}

/// 32-hex trace id of the current span's `OTel` context; empty string when none or invalid.
pub fn current_trace_id() -> String {
    let cx = tracing::Span::current().context();
    let span = cx.span();
    let sc = span.span_context();
    if sc.is_valid() {
        sc.trace_id().to_string()
    } else {
        String::new()
    }
}
