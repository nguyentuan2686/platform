//! Startup wiring: bind HTTP and serve until SIGTERM / Ctrl-C (graceful drain).
//! chart-svc is stateless: there are no external dependencies, so readiness is always UP.

use std::future::{Future, IntoFuture as _};
use std::sync::Arc;

use anyhow::Context as _;
use axum::Router;
use platform_core::http::ReadinessCheck;
use tokio::net::TcpListener;
use tracing::{info, warn};

use crate::adapters::engine::IztroAdapter;
use crate::adapters::http;
use crate::application::ports::ZiweiEngine;
use crate::application::service::{ChartService, ChartUseCases};
use crate::config::Config;

/// Shared handler state.
#[derive(Clone)]
pub struct AppState {
    pub readiness: Arc<dyn ReadinessCheck>,
    pub service: Arc<dyn ChartUseCases>,
}

impl AppState {
    /// State for a stateless service: ready whenever the process can serve.
    pub fn new() -> Self {
        Self {
            readiness: Arc::new(AlwaysReady),
            service: Arc::new(ChartService::new(IztroAdapter)),
        }
    }
}

impl AppState {
    /// State over a custom engine (tests inject failing ones).
    pub fn with_engine<E: ZiweiEngine + Send + Sync + 'static>(engine: E) -> Self {
        Self {
            readiness: Arc::new(AlwaysReady),
            service: Arc::new(ChartService::new(engine)),
        }
    }
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}

struct AlwaysReady;

#[async_trait::async_trait]
impl ReadinessCheck for AlwaysReady {
    async fn ready(&self) -> bool {
        true
    }
}

/// The full HTTP router (testable in-process).
pub fn router(state: AppState) -> Router {
    http::router(state)
}

/// Binds `0.0.0.0:<PORT>` and serves until a shutdown signal arrives.
pub async fn run(config: Config) -> anyhow::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", config.port))
        .await
        .with_context(|| format!("binding port {}", config.port))?;
    serve(listener, router(AppState::new()), shutdown_signal()).await
}

/// Serves `router` on `listener` until `shutdown` completes (tests use a random port).
pub async fn serve(
    listener: TcpListener,
    router: Router,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> anyhow::Result<()> {
    info!(addr = %listener.local_addr()?, "http server listening");
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown)
        .into_future()
        .await
        .context("http server")?;
    info!("shutdown complete");
    Ok(())
}

/// Completes on Ctrl-C or SIGTERM.
async fn shutdown_signal() {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn serve_stops_gracefully_on_shutdown() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let (tx, rx) = tokio::sync::oneshot::channel::<()>();
        let task = tokio::spawn(serve(listener, router(AppState::new()), async {
            let _ = rx.await;
        }));
        tx.send(()).unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(5), task)
            .await
            .expect("serve must stop after shutdown")
            .unwrap()
            .unwrap();
    }
}
