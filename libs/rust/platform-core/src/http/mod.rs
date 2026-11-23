//! HTTP building blocks for axum services: RFC 9457 problem details, health endpoints and
//! W3C trace propagation.

pub mod health;
pub mod problem;
pub mod tracing_mw;

pub use health::{health_router, health_router_with_timeout, ReadinessCheck};
pub use problem::{ApiError, ApiJson, ApiPath, ApiQuery, FieldError};
pub use tracing_mw::trace_layer;
