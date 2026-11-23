//! `/health/live` and `/health/ready` endpoints.
//!
//! `ReadinessCheck` uses `async_trait` (not native async-fn-in-trait) because the router holds it
//! as `Arc<dyn ReadinessCheck>`, and native AFIT traits are not dyn-compatible.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::get;
use axum::{Json, Router};
use serde_json::{json, Value};

/// Default upper bound for a readiness check before it is reported DOWN.
pub const DEFAULT_READY_TIMEOUT: Duration = Duration::from_secs(3);

/// Reports whether the service's dependencies (e.g. its database) are reachable.
#[async_trait::async_trait]
pub trait ReadinessCheck: Send + Sync {
    async fn ready(&self) -> bool;
}

#[derive(Clone)]
struct HealthState {
    check: Arc<dyn ReadinessCheck>,
    timeout: Duration,
}

/// Router exposing `GET /health/live` (always UP) and `GET /health/ready` (UP/200 or DOWN/503),
/// with the readiness check bounded by [`DEFAULT_READY_TIMEOUT`].
pub fn health_router(check: Arc<dyn ReadinessCheck>) -> Router {
    health_router_with_timeout(check, DEFAULT_READY_TIMEOUT)
}

/// Like [`health_router`] with an explicit readiness timeout; a timeout counts as DOWN.
pub fn health_router_with_timeout(check: Arc<dyn ReadinessCheck>, timeout: Duration) -> Router {
    Router::new()
        .route("/health/live", get(live))
        .route("/health/ready", get(ready))
        .with_state(HealthState { check, timeout })
}

async fn live() -> Json<Value> {
    Json(json!({"status": "UP"}))
}

async fn ready(State(state): State<HealthState>) -> (StatusCode, Json<Value>) {
    let up = tokio::time::timeout(state.timeout, state.check.ready())
        .await
        .unwrap_or(false);
    if up {
        (StatusCode::OK, Json(json!({"status": "UP"})))
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({"status": "DOWN"})),
        )
    }
}
