//! HTTP API: `GET /notifications` plus the platform health endpoints.
//!
//! `limit` is validated here (1..=200, 400 problem on field `limit` otherwise) even though the
//! application layer clamps: the HTTP contract rejects out-of-range values instead of silently
//! changing them.

use std::sync::Arc;

use axum::extract::State;
use axum::routing::get;
use axum::{Json, Router};
use platform_core::http::{
    health_router, http_metrics_layer, trace_layer, ApiError, ApiQuery, FieldError, ReadinessCheck,
};
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use tracing::warn;
use uuid::Uuid;

use crate::application::{AppError, NotificationRepositoryPort, NotificationService, MAX_LIMIT};
use crate::domain::{Channel, Notification};

#[derive(Debug, Deserialize)]
struct ListQuery {
    /// Signed so that negative values are a range error, not a parse error (same 400 either way).
    limit: Option<i64>,
}

/// Response item; JSON field names are camelCase, `createdAt` RFC 3339.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct NotificationDto {
    id: Uuid,
    order_id: Uuid,
    customer_id: String,
    channel: Channel,
    message: String,
    #[serde(with = "time::serde::rfc3339")]
    created_at: OffsetDateTime,
}

impl From<Notification> for NotificationDto {
    fn from(n: Notification) -> Self {
        Self {
            id: n.id(),
            order_id: n.order_id(),
            customer_id: n.customer_id().to_owned(),
            channel: n.channel(),
            message: n.message().to_owned(),
            created_at: n.created_at(),
        }
    }
}

/// Router with `GET /notifications`, `/health/live` and `/health/ready`, traced per request.
pub fn router<R: NotificationRepositoryPort + 'static>(
    service: Arc<NotificationService<R>>,
    readiness: Arc<dyn ReadinessCheck>,
) -> Router {
    Router::new()
        .route("/notifications", get(list::<R>))
        .with_state(service)
        .merge(health_router(readiness))
        .layer(http_metrics_layer())
        .layer(trace_layer())
}

async fn list<R: NotificationRepositoryPort + 'static>(
    State(service): State<Arc<NotificationService<R>>>,
    ApiQuery(query): ApiQuery<ListQuery>,
) -> Result<Json<Vec<NotificationDto>>, ApiError> {
    let limit = query.limit.map(validate_limit).transpose()?;
    let items = service.list(limit).await.map_err(to_api_error)?;
    Ok(Json(items.into_iter().map(NotificationDto::from).collect()))
}

fn validate_limit(limit: i64) -> Result<u32, ApiError> {
    u32::try_from(limit)
        .ok()
        .filter(|l| (1..=MAX_LIMIT).contains(l))
        .ok_or_else(limit_error)
}

fn limit_error() -> ApiError {
    ApiError::Validation(vec![FieldError {
        field: "limit".to_owned(),
        message: format!("must be between 1 and {MAX_LIMIT}"),
    }])
}

fn to_api_error(e: AppError) -> ApiError {
    match e {
        AppError::InvalidLimit => limit_error(),
        AppError::Repository(e) => ApiError::Internal(e),
        e @ AppError::Domain(_) => {
            warn!(error = %e, error_type = "UnexpectedDomainError", "domain error while listing");
            ApiError::Internal(anyhow::Error::new(e))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::DomainError;

    #[test]
    fn limit_bounds() {
        assert_eq!(validate_limit(1).ok(), Some(1));
        assert_eq!(validate_limit(200).ok(), Some(200));
        for bad in [0, 201, -1, i64::MAX, i64::MIN] {
            assert!(validate_limit(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn app_errors_map_to_problems() {
        assert!(
            matches!(to_api_error(AppError::InvalidLimit), ApiError::Validation(f) if f[0].field == "limit")
        );
        assert!(matches!(
            to_api_error(AppError::Domain(DomainError::InvalidTotal)),
            ApiError::Internal(_)
        ));
        assert!(matches!(
            to_api_error(AppError::Repository(anyhow::anyhow!("x"))),
            ApiError::Internal(_)
        ));
    }
}
