//! chart-svc binary: telemetry, config, then `app::run`; everything else lives in the library.

use chart_svc::config::{Config, DEFAULT_SERVICE_NAME};
use tracing::error;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let service_name = std::env::var("OTEL_SERVICE_NAME")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_SERVICE_NAME.to_owned());
    // Dropped last: flushes buffered spans after the app has stopped.
    let _telemetry = platform_core::telemetry::init(&service_name)?;

    let config = Config::from_env().inspect_err(|e| {
        error!(error = %format!("{e:#}"), error_type = "InvalidConfig", "invalid configuration");
    })?;

    chart_svc::app::run(config).await.inspect_err(|e| {
        error!(error = %format!("{e:#}"), error_type = "StartupFailed", "chart-svc stopped with an error");
    })
}
