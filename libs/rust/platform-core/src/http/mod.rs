//! HTTP building blocks for axum services: RFC 9457 problem details, health endpoints and
//! W3C trace propagation and RED metrics.

pub mod health;
pub mod metrics;
pub mod problem;
pub mod tracing_mw;

pub use health::{health_router, health_router_with_timeout, ReadinessCheck};
pub use metrics::{
    http_metrics_layer, http_metrics_layer_with_meter, HttpMetricsLayer, DURATION_BUCKETS_S,
};
pub use problem::{ApiError, ApiJson, ApiPath, ApiQuery, FieldError};
pub use tracing_mw::trace_layer;
