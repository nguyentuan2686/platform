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

use crate::application::{
    NotificationRepositoryPort, OutboxItem, OutboxPort, PaymentRepositoryPort,
};
use crate::domain::{Channel, Money, Notification, RevenueLine, TopUp};

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
    #[tracing::instrument(
        skip_all,
        name = "INSERT notify.notifications",
        fields(otel.kind = "client", db.system = "postgresql", db.operation = "INSERT", db.sql.table = "notifications")
    )]
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

    #[tracing::instrument(
        skip_all,
        name = "SELECT notify.notifications",
        fields(otel.kind = "client", db.system = "postgresql", db.operation = "SELECT", db.sql.table = "notifications")
    )]
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

/// `payments` and `telegram_outbox` (migration 0002).
#[derive(Clone)]
pub struct TelegramStoreAdapter {
    pool: PgPool,
}

impl TelegramStoreAdapter {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl PaymentRepositoryPort for TelegramStoreAdapter {
    #[tracing::instrument(
        skip_all,
        name = "INSERT notify.payments",
        fields(otel.kind = "client", db.system = "postgresql", db.operation = "INSERT", db.sql.table = "payments")
    )]
    async fn save(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        t: &TopUp,
        announce: Option<&str>,
        now: OffsetDateTime,
    ) -> anyhow::Result<bool> {
        let inserted = sqlx::query(
            "INSERT INTO payments (order_id, product_code, amount_minor, currency, paid_at) \
             VALUES ($1, $2, $3, $4, $5) ON CONFLICT (order_id) DO NOTHING",
        )
        .bind(t.order_id)
        .bind(&t.product_code)
        .bind(t.money.minor)
        .bind(&t.money.currency)
        .bind(t.paid_at)
        .execute(&mut **tx)
        .await
        .context("inserting payment")?
        .rows_affected();
        if inserted == 0 {
            return Ok(false);
        }
        if let Some(text) = announce {
            sqlx::query(
                "INSERT INTO telegram_outbox (key, text, created_at, next_attempt_at) VALUES ($1, $2, $3, $3) \
                 ON CONFLICT (key) DO NOTHING",
            )
            .bind(format!("payment:{}", t.order_id))
            .bind(text)
            .bind(now)
            .execute(&mut **tx)
            .await
            .context("queueing payment message")?;
        }
        Ok(true)
    }

    async fn revenue(
        &self,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ) -> anyhow::Result<Vec<RevenueLine>> {
        let rows: Vec<(String, String, i64, i64)> = sqlx::query_as(
            "SELECT product_code, currency, count(*), sum(amount_minor)::bigint FROM payments \
             WHERE paid_at >= $1 AND paid_at < $2 GROUP BY product_code, currency \
             ORDER BY sum(amount_minor) DESC, product_code, currency",
        )
        .bind(from)
        .bind(to)
        .fetch_all(&self.pool)
        .await
        .context("summing payments")?;
        Ok(rows
            .into_iter()
            .map(|(product_code, currency, count, minor)| RevenueLine {
                product_code,
                count,
                total: Money { minor, currency },
            })
            .collect())
    }
}

#[async_trait]
impl OutboxPort for TelegramStoreAdapter {
    async fn contains(&self, key: &str) -> anyhow::Result<bool> {
        let n: (i64,) = sqlx::query_as("SELECT count(*) FROM telegram_outbox WHERE key = $1")
            .bind(key)
            .fetch_one(&self.pool)
            .await
            .context("reading outbox")?;
        Ok(n.0 > 0)
    }

    async fn enqueue(&self, key: &str, text: &str, now: OffsetDateTime) -> anyhow::Result<bool> {
        Ok(sqlx::query(
            "INSERT INTO telegram_outbox (key, text, created_at, next_attempt_at) VALUES ($1, $2, $3, $3) \
             ON CONFLICT (key) DO NOTHING",
        )
        .bind(key)
        .bind(text)
        .bind(now)
        .execute(&self.pool)
        .await
        .context("queueing message")?
        .rows_affected()
            > 0)
    }

    async fn due(&self, now: OffsetDateTime, limit: u32) -> anyhow::Result<Vec<OutboxItem>> {
        let rows: Vec<(i64, String, i32, OffsetDateTime)> = sqlx::query_as(
            "SELECT id, text, attempts, created_at FROM telegram_outbox \
             WHERE status = 'pending' AND next_attempt_at <= $1 ORDER BY id LIMIT $2",
        )
        .bind(now)
        .bind(i64::from(limit))
        .fetch_all(&self.pool)
        .await
        .context("reading due messages")?;
        Ok(rows
            .into_iter()
            .map(|(id, text, attempts, created_at)| OutboxItem {
                id,
                text,
                attempts,
                created_at,
            })
            .collect())
    }

    async fn mark_sent(&self, id: i64, now: OffsetDateTime) -> anyhow::Result<()> {
        sqlx::query("UPDATE telegram_outbox SET status = 'sent', sent_at = $2 WHERE id = $1")
            .bind(id)
            .bind(now)
            .execute(&self.pool)
            .await
            .context("marking message sent")?;
        Ok(())
    }

    async fn mark_expired(&self, id: i64) -> anyhow::Result<()> {
        sqlx::query("UPDATE telegram_outbox SET status = 'expired' WHERE id = $1")
            .bind(id)
            .execute(&self.pool)
            .await
            .context("expiring message")?;
        Ok(())
    }

    async fn retry_later(
        &self,
        id: i64,
        attempts: i32,
        next: OffsetDateTime,
    ) -> anyhow::Result<()> {
        sqlx::query("UPDATE telegram_outbox SET attempts = $2, next_attempt_at = $3 WHERE id = $1")
            .bind(id)
            .bind(attempts)
            .bind(next)
            .execute(&self.pool)
            .await
            .context("rescheduling message")?;
        Ok(())
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
