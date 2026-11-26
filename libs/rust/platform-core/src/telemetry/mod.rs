//! Telemetry: JSON logs on stdout and optional OTLP (http/protobuf) trace and metric export.

pub mod json_layer;

use std::time::Duration;

use anyhow::Context as _;

use opentelemetry::trace::{TraceContextExt, TracerProvider as _};
use opentelemetry_sdk::metrics::{PeriodicReader, SdkMeterProvider, Temporality};
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::SdkTracerProvider;
use tracing_opentelemetry::OpenTelemetrySpanExt;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::EnvFilter;

pub use json_layer::JsonLayer;

/// Flushes and shuts down the tracer and meter providers on drop.
pub struct TelemetryGuard {
    provider: Option<SdkTracerProvider>,
    meter_provider: Option<SdkMeterProvider>,
}

/// Upper bound for flushing and shutting down the exporters on drop. A blackholed collector must
/// not hold the process past Kubernetes' termination grace period.
const SHUTDOWN_BUDGET: Duration = Duration::from_secs(5);

/// How often the same OTel-internal (exporter) log source may emit a line.
const SDK_LOG_WINDOW: Duration = Duration::from_secs(300);

impl Drop for TelemetryGuard {
    fn drop(&mut self) {
        let tracer = self.provider.take();
        let meter = self.meter_provider.take();
        if tracer.is_none() && meter.is_none() {
            return;
        }
        // The SDK's blocking exporters can outlive their own timeouts, so the shutdown runs on a
        // helper thread and is abandoned (the thread is detached) once the budget is spent.
        let (tx, rx) = std::sync::mpsc::channel();
        let spawned = std::thread::Builder::new()
            .name("telemetry-shutdown".into())
            .spawn(move || {
                if let Some(p) = tracer {
                    let _ = p.shutdown_with_timeout(SHUTDOWN_BUDGET / 2);
                }
                if let Some(p) = meter {
                    let _ = p.shutdown();
                }
                let _ = tx.send(());
            });
        if spawned.is_ok() {
            // Timeout or disconnect both mean: stop waiting.
            let _ = rx.recv_timeout(SHUTDOWN_BUDGET);
        }
    }
}

/// `RUST_LOG` when set; otherwise `info` for our code and `warn` for the exporter stack
/// (`opentelemetry*`, `hyper_util`, `reqwest`, `h2`), whose INFO chatter is not ours.
pub fn env_filter() -> EnvFilter {
    EnvFilter::try_from_default_env().unwrap_or_else(|_| {
        EnvFilter::new(
            "info,opentelemetry=warn,opentelemetry_sdk=warn,opentelemetry_otlp=warn,\
             opentelemetry_http=warn,hyper_util=warn,reqwest=warn,h2=warn",
        )
    })
}

/// Installs the global tracing subscriber (JSON layer on stdout, `RUST_LOG` filter defaulting to
/// `info`, an `OTel` tracer provider always, the OTLP exporter only when
/// `OTEL_EXPORTER_OTLP_ENDPOINT` is set) and the W3C propagator.
///
/// Returns an error (never panics) if a global subscriber is already installed.
pub fn init(service_name: &str) -> anyhow::Result<TelemetryGuard> {
    opentelemetry::global::set_text_map_propagator(TraceContextPropagator::new());

    let filter = env_filter();
    let json = JsonLayer::new(service_name, std::io::stdout).with_sdk_throttle(SDK_LOG_WINDOW);

    // The tracer provider and OTel layer are always installed so an inbound `traceparent` is
    // continued (and `trace_id`/`span_id` reach the logs) even without an exporter; spans are
    // only exported when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.
    let resource = opentelemetry_sdk::Resource::builder()
        .with_service_name(service_name.to_owned())
        .build();
    let otlp = std::env::var("OTEL_EXPORTER_OTLP_ENDPOINT").is_ok_and(|v| !v.is_empty());
    let mut builder = SdkTracerProvider::builder().with_resource(resource.clone());
    if otlp {
        let exporter = opentelemetry_otlp::SpanExporter::builder()
            .with_http()
            .build()
            .context("building OTLP span exporter")?;
        builder = builder.with_batch_exporter(exporter);
    }
    let provider = builder.build();

    // The meter provider is always installed (instruments are cheap no-ops without a reader);
    // metrics are only exported when `OTEL_EXPORTER_OTLP_ENDPOINT` is set. The periodic reader
    // honours `OTEL_METRIC_EXPORT_INTERVAL` (ms) and exports from its own thread.
    let mut meter_builder = SdkMeterProvider::builder().with_resource(resource);
    if otlp {
        let exporter = opentelemetry_otlp::MetricExporter::builder()
            .with_http()
            .with_temporality(Temporality::Cumulative)
            .build()
            .context("building OTLP metric exporter")?;
        meter_builder = meter_builder.with_reader(PeriodicReader::builder(exporter).build());
    }
    let meter_provider = meter_builder.build();
    let otel_layer =
        tracing_opentelemetry::layer().with_tracer(provider.tracer(service_name.to_owned()));

    tracing_subscriber::registry()
        .with(filter)
        .with(otel_layer)
        .with(json)
        .try_init()
        .context("a global tracing subscriber is already installed")?;

    opentelemetry::global::set_meter_provider(meter_provider.clone());

    Ok(TelemetryGuard {
        provider: Some(provider),
        meter_provider: Some(meter_provider),
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
