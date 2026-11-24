//! notify-svc binary: telemetry, config, signals; everything else lives in the library.

use notify_svc::config::{Config, DEFAULT_SERVICE_NAME};
use tokio_util::sync::CancellationToken;
use tracing::{error, info, warn};

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

    let shutdown = CancellationToken::new();
    tokio::spawn(cancel_on_signal(shutdown.clone()));

    notify_svc::app::run(config, shutdown).await.inspect_err(|e| {
        error!(error = %format!("{e:#}"), error_type = "StartupFailed", "notify-svc stopped with an error");
    })
}

/// Cancels `shutdown` on Ctrl-C or SIGTERM.
async fn cancel_on_signal(shutdown: CancellationToken) {
    let ctrl_c = async {
        if let Err(e) = tokio::signal::ctrl_c().await {
            warn!(error = %e, error_type = "SignalHandler", "cannot listen for Ctrl-C");
            std::future::pending::<()>().await;
        }
    };
    tokio::select! {
        () = ctrl_c => {}
        () = sigterm() => {}
    }
    info!("shutdown signal received");
    shutdown.cancel();
}

#[cfg(unix)]
async fn sigterm() {
    use tokio::signal::unix::{signal, SignalKind};
    match signal(SignalKind::terminate()) {
        Ok(mut s) => {
            s.recv().await;
        }
        Err(e) => {
            warn!(error = %e, error_type = "SignalHandler", "cannot listen for SIGTERM");
            std::future::pending::<()>().await;
        }
    }
}

#[cfg(not(unix))]
async fn sigterm() {
    std::future::pending::<()>().await;
}
