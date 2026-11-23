//! Pure domain: no framework or IO dependencies.
pub mod notification;

pub use notification::{Channel, DomainError, Notification, OrderPlaced};
