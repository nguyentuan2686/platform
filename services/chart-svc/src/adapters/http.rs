//! HTTP API: platform health endpoints and a problem+json 404 fallback.

use axum::extract::Request;
use axum::Router;
use platform_core::http::{health_router, trace_layer, ApiError};

use crate::app::AppState;

/// Router with `/health/live`, `/health/ready` and a `not-found` problem fallback, traced per
/// request.
pub fn router(state: AppState) -> Router {
    health_router(state.readiness)
        .fallback(not_found)
        .layer(trace_layer())
}

async fn not_found(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: req.uri().path().to_owned(),
    }
}
