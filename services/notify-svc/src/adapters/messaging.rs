//! Kafka: the `OrderPlaced` and `PaymentSucceeded` handlers run by `platform_core::messaging::run_consumer`.

use std::sync::Arc;

use async_trait::async_trait;
use platform_core::messaging::{Envelope, EventHandler, MalformedEvent};
use sqlx::postgres::PgDatabaseError;
use sqlx::{Postgres, Transaction};
use tracing::info;

use crate::application::{
    AppError, NotificationRepositoryPort, NotificationService, PaymentRepositoryPort,
    TelegramError, TopUpService,
};
use crate::domain::{OrderPlaced, PaymentSucceeded};

pub const TOPIC: &str = "orders.order-placed.v1";
pub const EVENT_TYPE: &str = "OrderPlaced";
/// Idempotency key namespace in `platform.processed_event`.
pub const CONSUMER_NAME: &str = "notify-svc";

/// Records one notification per `OrderPlaced` event.
///
/// Invalid `data` (missing/mistyped field, or rejected by the domain, e.g. a malformed total) can
/// never succeed, so it is returned as [`MalformedEvent`]: the runner logs it at WARN and commits
/// past it instead of retrying forever. The same applies to data Postgres rejects (SQLSTATE class
/// 22/23, see [`rejected_by_database`]); every other repository error is transient and retried.
/// Cancel-safe: the only await is inside the runner's transaction, which is rolled back if dropped.
pub struct OrderPlacedHandler<R> {
    service: Arc<NotificationService<R>>,
}

impl<R> OrderPlacedHandler<R> {
    pub fn new(service: Arc<NotificationService<R>>) -> Self {
        Self { service }
    }
}

/// Parses `data` without ever echoing its values (they may hold PII) into the error.
fn parse_data(data: &serde_json::Value) -> Result<OrderPlaced, MalformedEvent> {
    parse_as(data, "an OrderPlaced")
}

/// Generic form of [`parse_data`]: `what` names the event, with its article (`an OrderPlaced`).
fn parse_as<T: serde::de::DeserializeOwned>(
    data: &serde_json::Value,
    what: &str,
) -> Result<T, MalformedEvent> {
    serde_path_to_error::deserialize(data).map_err(|e| {
        let inner = e.inner().to_string();
        let path = e.path().to_string();
        if inner.starts_with("missing field") {
            MalformedEvent::new(format!("data: {inner}"))
        } else if path.is_empty() || path == "." {
            MalformedEvent::new(format!("data is not {what} object"))
        } else {
            MalformedEvent::new(format!("data.{path} has an invalid value"))
        }
    })
}

/// SQLSTATE class 22 (data exception, e.g. `\u0000` in text) or 23 (integrity constraint
/// violation): Postgres rejects the event's data itself, so retrying can never succeed.
fn is_invalid_data_sqlstate(code: &str) -> bool {
    code.len() == 5 && (code.starts_with("22") || code.starts_with("23"))
}

/// `Some` when a repository error is Postgres rejecting the data (see
/// [`is_invalid_data_sqlstate`]). The reason names the SQLSTATE and, when Postgres reports them,
/// the column or constraint; never the server message, which can echo values.
fn rejected_by_database(e: &anyhow::Error) -> Option<MalformedEvent> {
    let db = e
        .chain()
        .find_map(|c| c.downcast_ref::<sqlx::Error>())?
        .as_database_error()?;
    let code = db.code()?;
    if !is_invalid_data_sqlstate(&code) {
        return None;
    }
    let column = db
        .try_downcast_ref::<PgDatabaseError>()
        .and_then(PgDatabaseError::column);
    let mut reason = format!("data rejected by the database (SQLSTATE {code}");
    if let Some(column) = column {
        reason.push_str(", column ");
        reason.push_str(column);
    }
    if let Some(constraint) = db.constraint() {
        reason.push_str(", constraint ");
        reason.push_str(constraint);
    }
    reason.push(')');
    Some(MalformedEvent::new(reason))
}

#[async_trait]
impl<R: NotificationRepositoryPort + 'static> EventHandler for OrderPlacedHandler<R> {
    fn event_type(&self) -> &'static str {
        EVENT_TYPE
    }

    async fn handle(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        event: &Envelope,
    ) -> anyhow::Result<()> {
        let data = parse_data(&event.data)?;
        match self.service.record(tx, &data).await {
            Ok(n) => {
                info!(
                    event_id = %event.event_id,
                    order_id = %n.order_id(),
                    notification_id = %n.id(),
                    "notification recorded"
                );
                Ok(())
            }
            Err(AppError::Domain(e)) => {
                Err(MalformedEvent::new(format!("data rejected: {e}")).into())
            }
            Err(AppError::Repository(e)) => match rejected_by_database(&e) {
                Some(m) => Err(m.into()),
                None => Err(anyhow::Error::new(AppError::Repository(e))),
            },
            Err(e) => Err(anyhow::Error::new(e)),
        }
    }
}

pub const PAYMENTS_TOPIC: &str = "payments.payment-succeeded.v1";
pub const PAYMENT_EVENT_TYPE: &str = "PaymentSucceeded";
/// Idempotency namespace and default consumer group of the payments consumer.
pub const PAYMENTS_CONSUMER_NAME: &str = "notify-svc-payments";

/// Records each `PaymentSucceeded` top-up (revenue) and queues its Telegram message when recent.
///
/// Same error policy as [`OrderPlacedHandler`]: invalid data is a [`MalformedEvent`] (logged, skipped), a database
/// rejection of the data too, other repository errors are retried. Only ids are logged, never the amount or package.
pub struct PaymentSucceededHandler<P> {
    service: Arc<TopUpService<P>>,
}

impl<P> PaymentSucceededHandler<P> {
    pub fn new(service: Arc<TopUpService<P>>) -> Self {
        Self { service }
    }
}

#[async_trait]
impl<P: PaymentRepositoryPort + 'static> EventHandler for PaymentSucceededHandler<P> {
    fn event_type(&self) -> &'static str {
        PAYMENT_EVENT_TYPE
    }

    async fn handle(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        event: &Envelope,
    ) -> anyhow::Result<()> {
        let data: PaymentSucceeded = parse_as(&event.data, "a PaymentSucceeded")?;
        match self.service.record(tx, &data).await {
            Ok(outcome) => {
                info!(event_id = %event.event_id, order_id = %data.order_id, outcome = ?outcome, "top-up recorded");
                Ok(())
            }
            Err(TelegramError::TopUp(e)) => {
                Err(MalformedEvent::new(format!("data rejected: {e}")).into())
            }
            Err(TelegramError::Repository(e)) => match rejected_by_database(&e) {
                Some(m) => Err(m.into()),
                None => Err(anyhow::Error::new(TelegramError::Repository(e))),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn parses_valid_data_and_ignores_extra_fields() {
        let d = json!({"order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d", "customer_id": "c",
                       "total": "1.00", "item_count": 1, "coupon": "X"});
        assert_eq!(parse_data(&d).unwrap().item_count, 1);
    }

    #[test]
    fn errors_name_the_field_but_not_the_value() {
        let missing = json!({"order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d"});
        assert!(parse_data(&missing)
            .unwrap_err()
            .reason()
            .contains("missing field"));

        let wrong = json!({"order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d", "customer_id": "c",
                           "total": "1.00", "item_count": "secret-value"});
        let reason = parse_data(&wrong).unwrap_err().reason().to_owned();
        assert!(reason.contains("item_count"), "{reason}");
        assert!(!reason.contains("secret-value"), "{reason}");

        assert_eq!(
            parse_data(&json!("secret-value")).unwrap_err().reason(),
            "data is not an OrderPlaced object"
        );
    }

    /// Synthetic driver error: lets the classification be tested without a database.
    #[derive(Debug)]
    struct FakeDbError {
        code: &'static str,
        constraint: Option<&'static str>,
    }
    impl std::fmt::Display for FakeDbError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("invalid input syntax: \"secret-value\"")
        }
    }
    impl std::error::Error for FakeDbError {}
    impl sqlx::error::DatabaseError for FakeDbError {
        fn message(&self) -> &'static str {
            "invalid input syntax: \"secret-value\""
        }
        fn code(&self) -> Option<std::borrow::Cow<'_, str>> {
            Some(self.code.into())
        }
        fn constraint(&self) -> Option<&str> {
            self.constraint
        }
        fn as_error(&self) -> &(dyn std::error::Error + Send + Sync + 'static) {
            self
        }
        fn as_error_mut(&mut self) -> &mut (dyn std::error::Error + Send + Sync + 'static) {
            self
        }
        fn into_error(self: Box<Self>) -> Box<dyn std::error::Error + Send + Sync + 'static> {
            self
        }
        fn kind(&self) -> sqlx::error::ErrorKind {
            sqlx::error::ErrorKind::Other
        }
    }

    fn repo_error(code: &'static str, constraint: Option<&'static str>) -> anyhow::Error {
        anyhow::Error::new(sqlx::Error::Database(Box::new(FakeDbError {
            code,
            constraint,
        })))
        .context("inserting notification")
    }

    #[test]
    fn data_exception_and_integrity_classes_are_invalid_data() {
        for code in ["22021", "22P02", "22001", "23505", "23502", "23514"] {
            assert!(is_invalid_data_sqlstate(code), "{code}");
        }
        for code in [
            "40001", "40P01", "08006", "53300", "57014", "42P01", "2", "",
        ] {
            assert!(!is_invalid_data_sqlstate(code), "{code}");
        }
    }

    #[test]
    fn database_rejection_becomes_malformed_naming_code_and_constraint_not_values() {
        let m = rejected_by_database(&repo_error("23505", Some("notifications_pkey")))
            .expect("integrity violation is permanent");
        assert!(m.reason().contains("23505"), "{}", m.reason());
        assert!(m.reason().contains("notifications_pkey"), "{}", m.reason());
        assert!(!m.reason().contains("secret-value"), "{}", m.reason());

        let m = rejected_by_database(&repo_error("22021", None)).expect("data exception");
        assert!(m.reason().contains("22021"), "{}", m.reason());
        assert!(!m.reason().contains("secret-value"), "{}", m.reason());
    }

    #[test]
    fn transient_and_non_database_errors_stay_retryable() {
        assert!(rejected_by_database(&repo_error("40001", None)).is_none());
        assert!(rejected_by_database(&repo_error("08006", None)).is_none());
        assert!(rejected_by_database(&anyhow::Error::new(sqlx::Error::PoolTimedOut)).is_none());
        assert!(rejected_by_database(&anyhow::anyhow!("db down")).is_none());
    }

    #[test]
    fn parses_payment_data_and_never_echoes_values() {
        let d = json!({"order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d", "order_code": "TV123",
                       "account_id": "1a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d", "product_code": "CREDIT_5", "site": "tuvi",
                       "amount": "50000.00", "currency": "VND", "provider": "SEPAY",
                       "paid_at": "2026-11-28T07:05:09Z", "chart_profile_id": null, "future_field": 1});
        let p: PaymentSucceeded = parse_as(&d, "a PaymentSucceeded").unwrap();
        assert_eq!(
            (p.product_code.as_str(), p.amount.as_str()),
            ("CREDIT_5", "50000.00")
        );

        let bad = json!({"order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d", "product_code": "CREDIT_5",
                         "amount": "50000.00", "currency": "VND", "paid_at": "secret-value"});
        let reason = parse_as::<PaymentSucceeded>(&bad, "a PaymentSucceeded")
            .unwrap_err()
            .reason()
            .to_owned();
        assert!(
            reason.contains("paid_at") && !reason.contains("secret-value"),
            "{reason}"
        );
        assert_eq!(
            parse_as::<PaymentSucceeded>(&json!("x"), "a PaymentSucceeded")
                .unwrap_err()
                .reason(),
            "data is not a PaymentSucceeded object"
        );
    }
}
