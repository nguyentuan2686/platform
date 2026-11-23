//! `/health/live` and `/health/ready` endpoints.
//!
//! `ReadinessCheck` uses `async_trait` (not native async-fn-in-trait) because the router holds it
//! as `Arc<dyn ReadinessCheck>`, and native AFIT traits are not dyn-compatible.

use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::get;
use axum::{Json, Router};
use serde_json::{json, Value};

/// Reports whether the service's dependencies (e.g. its database) are reachable.
#[async_trait::async_trait]
pub trait ReadinessCheck: Send + Sync {
    async fn ready(&self) -> bool;
}

/// Router exposing `GET /health/live` (always UP) and `GET /health/ready` (UP/200 or DOWN/503).
pub fn health_router(check: Arc<dyn ReadinessCheck>) -> Router {
    Router::new()
        .route("/health/live", get(live))
        .route("/health/ready", get(ready))
        .with_state(check)
}

async fn live() -> Json<Value> {
    Json(json!({"status": "UP"}))
}

async fn ready(State(check): State<Arc<dyn ReadinessCheck>>) -> (StatusCode, Json<Value>) {
    if check.ready().await {
        (StatusCode::OK, Json(json!({"status": "UP"})))
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({"status": "DOWN"})),
        )
    }
}
