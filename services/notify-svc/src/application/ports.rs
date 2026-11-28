use async_trait::async_trait;
use sqlx::{Postgres, Transaction};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::domain::{ErrorRate, Notification, RevenueLine, TopUp, Uses};

/// The transaction parameter lets the insert share the consumer's idempotency transaction.
#[async_trait]
pub trait NotificationRepositoryPort: Send + Sync {
    async fn save(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        n: &Notification,
    ) -> anyhow::Result<()>;
    async fn list_recent(&self, limit: u32) -> anyhow::Result<Vec<Notification>>;
}

pub trait Clock: Send + Sync {
    fn now(&self) -> OffsetDateTime;
}

pub trait IdGenerator: Send + Sync {
    fn next_id(&self) -> Uuid;
}

/// Top-ups (revenue) and the Telegram outbox.
#[async_trait]
pub trait PaymentRepositoryPort: Send + Sync {
    /// Stores the top-up and, when `announce` holds a text, its outbox message `payment:<order_id>`, in the consumer's
    /// transaction. False when the order was already stored (nothing written then).
    async fn save(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        topup: &TopUp,
        announce: Option<&str>,
        now: OffsetDateTime,
    ) -> anyhow::Result<bool>;
    /// Count and total per (package, currency) of the top-ups paid in `[from, to)`.
    async fn revenue(
        &self,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ) -> anyhow::Result<Vec<RevenueLine>>;
}

/// A pending Telegram message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboxItem {
    pub id: i64,
    pub text: String,
    pub attempts: i32,
    pub created_at: OffsetDateTime,
}

#[async_trait]
pub trait OutboxPort: Send + Sync {
    async fn contains(&self, key: &str) -> anyhow::Result<bool>;
    /// Inserts unless `key` exists; true when inserted.
    async fn enqueue(&self, key: &str, text: &str, now: OffsetDateTime) -> anyhow::Result<bool>;
    /// Pending messages whose next attempt is due, oldest first.
    async fn due(&self, now: OffsetDateTime, limit: u32) -> anyhow::Result<Vec<OutboxItem>>;
    async fn mark_sent(&self, id: i64, now: OffsetDateTime) -> anyhow::Result<()>;
    async fn mark_expired(&self, id: i64) -> anyhow::Result<()>;
    async fn retry_later(&self, id: i64, attempts: i32, next: OffsetDateTime)
        -> anyhow::Result<()>;
}

/// Sends one text to the owner's chat.
#[async_trait]
pub trait MessengerPort: Send + Sync {
    async fn send(&self, text: &str) -> anyhow::Result<()>;
}

/// The day's numbers that live outside this service (Prometheus, Grafana).
#[async_trait]
pub trait DayMetricsPort: Send + Sync {
    async fn uses(&self, from: OffsetDateTime, to: OffsetDateTime) -> anyhow::Result<Uses>;
    async fn error_rates(
        &self,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ) -> anyhow::Result<Vec<ErrorRate>>;
    async fn alerts_fired(&self, from: OffsetDateTime, to: OffsetDateTime) -> anyhow::Result<u64>;
}
