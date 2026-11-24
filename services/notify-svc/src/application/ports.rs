use async_trait::async_trait;
use sqlx::{Postgres, Transaction};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::domain::Notification;

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
