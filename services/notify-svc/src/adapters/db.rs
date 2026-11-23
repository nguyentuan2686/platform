//! Postgres persistence: pool setup, migrations, the notification repository and readiness.

use std::str::FromStr as _;
use std::time::Duration;

use anyhow::Context as _;
use async_trait::async_trait;
use platform_core::db::migrate_platform;
use platform_core::http::ReadinessCheck;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{PgPool, Postgres, Transaction};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::application::NotificationRepositoryPort;
use crate::domain::{Channel, Notification};

/// Upper bound for waiting on a pooled connection; a hung DB fails fast (consumer retries).
pub const ACQUIRE_TIMEOUT: Duration = Duration::from_secs(5);
/// Server-side `statement_timeout` set on every connection.
pub const STATEMENT_TIMEOUT: &str = "10s";
const MAX_CONNECTIONS: u32 = 10;

/// Connects a pool with [`ACQUIRE_TIMEOUT`] and a per-connection [`STATEMENT_TIMEOUT`]
/// (startup parameter, so it also applies to reconnects). Errors never contain the URL.
pub async fn connect(url: &str) -> anyhow::Result<PgPool> {
    let options = PgConnectOptions::from_str(url)
        .map_err(|_| anyhow::anyhow!("DB_URL is not a valid Postgres URL"))?
        .options([("statement_timeout", STATEMENT_TIMEOUT)]);
    PgPoolOptions::new()
        .max_connections(MAX_CONNECTIONS)
        .acquire_timeout(ACQUIRE_TIMEOUT)
        .connect_with(options)
        .await
        .context("connecting to the database")
}

/// Platform migrations (`platform` schema) first, then the service's own (`public._sqlx_migrations`).
pub async fn migrate(pool: &PgPool) -> anyhow::Result<()> {
    migrate_platform(pool).await?;
    sqlx::migrate!("./migrations")
        .run(pool)
        .await
        .context("applying notify-svc migrations")?;
    Ok(())
}

pub struct NotificationRepositoryAdapter {
    pool: PgPool,
}

impl NotificationRepositoryAdapter {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

type Row = (Uuid, Uuid, String, String, String, OffsetDateTime);

fn to_domain(
    (id, order_id, customer_id, channel, message, created_at): Row,
) -> anyhow::Result<Notification> {
    let channel = channel
        .parse::<Channel>()
        .with_context(|| format!("notification {id}"))?;
    Ok(Notification::restore(
        id,
        order_id,
        customer_id,
        channel,
        message,
        created_at,
    ))
}

#[async_trait]
impl NotificationRepositoryPort for NotificationRepositoryAdapter {
    async fn save(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        n: &Notification,
    ) -> anyhow::Result<()> {
        sqlx::query(
            "INSERT INTO notifications (id, order_id, customer_id, channel, message, created_at) \
             VALUES ($1, $2, $3, $4, $5, $6)",
        )
        .bind(n.id())
        .bind(n.order_id())
        .bind(n.customer_id())
        .bind(n.channel().as_str())
        .bind(n.message())
        .bind(n.created_at())
        .execute(&mut **tx)
        .await
        .context("inserting notification")?;
        Ok(())
    }

    async fn list_recent(&self, limit: u32) -> anyhow::Result<Vec<Notification>> {
        let rows: Vec<Row> = sqlx::query_as(
            "SELECT id, order_id, customer_id, channel, message, created_at FROM notifications \
             ORDER BY created_at DESC, id DESC LIMIT $1",
        )
        .bind(i64::from(limit))
        .fetch_all(&self.pool)
        .await
        .context("listing notifications")?;
        rows.into_iter().map(to_domain).collect()
    }
}

/// Ready when a trivial query succeeds.
pub struct PgReadiness {
    pool: PgPool,
}

impl PgReadiness {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl ReadinessCheck for PgReadiness {
    async fn ready(&self) -> bool {
        sqlx::query("SELECT 1").execute(&self.pool).await.is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_channel_in_row_is_an_error() {
        let row = |c: &str| -> Row {
            (
                Uuid::nil(),
                Uuid::nil(),
                "c".into(),
                c.into(),
                "m".into(),
                OffsetDateTime::UNIX_EPOCH,
            )
        };
        assert_eq!(to_domain(row("log")).unwrap().channel(), Channel::Log);
        assert!(to_domain(row("sms")).is_err());
    }

    #[tokio::test]
    async fn invalid_url_error_does_not_echo_it() {
        let err = connect("not a url secret-pw").await.unwrap_err();
        assert!(!format!("{err:#}").contains("secret-pw"), "{err:#}");
    }
}
