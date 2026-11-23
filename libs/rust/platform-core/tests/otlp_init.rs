//! Separate test binary: owns the process-global subscriber and env var.
//! Guards against blocking reqwest misbehaving when `init` runs inside a tokio runtime.

use platform_core::telemetry::init;

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_with_otlp_endpoint_inside_runtime_does_not_panic() {
    std::env::set_var("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:1");
    let guard = init("otlp-smoke").expect("init with OTLP endpoint");
    {
        let span = tracing::info_span!("smoke");
        let _g = span.enter();
        tracing::info!("inside span");
    }
    // Dropping flushes/shuts down the provider; the unreachable endpoint must not panic or hang.
    drop(guard);
}
