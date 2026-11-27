//! HTTP API: the sample `GET /v1/info`, the platform health endpoints and problem+json
//! `not-found` fallbacks (unknown path, wrong method). JSON DTOs live only here; the domain
//! stays serde-free.

use axum::extract::{Request, State};
use axum::routing::get;
use axum::{Json, Router};
use platform_core::http::{health_router, http_metrics_layer, trace_layer, ApiError};
use serde::Serialize;

use crate::app::AppState;

/// Router with `/v1/info`, `/health/live`, `/health/ready` and `not-found` problem fallbacks
/// for unknown paths and wrong methods, traced and metered per request.
pub fn router(state: AppState) -> Router {
    let readiness = state.readiness.clone();
    Router::new()
        .route("/v1/info", get(info))
        .with_state(state)
        .merge(health_router(readiness))
        .fallback(not_found)
        .method_not_allowed_fallback(method_not_allowed)
        .layer(http_metrics_layer())
        .layer(trace_layer())
}

/// `GET /v1/info` response body.
#[derive(Debug, Serialize)]
struct InfoResponse {
    service: String,
}

async fn info(State(state): State<AppState>) -> Json<InfoResponse> {
    Json(InfoResponse {
        service: state.service.info().name().to_owned(),
    })
}

async fn not_found(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: req.uri().path().to_owned(),
    }
}

/// A known path with the wrong method. platform-core's `ApiError` has no 405 variant, so
/// this is the closest one, a `not-found` problem naming the method and path.
async fn method_not_allowed(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: format!("{} {}", req.method(), req.uri().path()),
    }
}
