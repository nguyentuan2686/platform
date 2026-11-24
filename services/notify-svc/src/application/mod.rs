//! Use cases and the ports they depend on.
pub mod ports;
pub mod service;

pub use ports::{Clock, IdGenerator, NotificationRepositoryPort};
pub use service::{AppError, NotificationService, DEFAULT_LIMIT, MAX_LIMIT};
