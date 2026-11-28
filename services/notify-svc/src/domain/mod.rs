//! Pure domain: no framework or IO dependencies.
pub mod notification;
pub mod summary;
pub mod topup;

pub use notification::{Channel, DomainError, Notification, OrderPlaced, UnknownChannel};
pub use summary::{day_range, due_day, DailySummary, ErrorRate, RevenueLine, Uses};
pub use topup::{Money, PaymentSucceeded, TopUp, TopUpError};
