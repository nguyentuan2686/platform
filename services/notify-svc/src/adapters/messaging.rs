//! Kafka: the `OrderPlaced` handler run by `platform_core::messaging::run_consumer`.

use std::sync::Arc;

use async_trait::async_trait;
use platform_core::messaging::{Envelope, EventHandler, MalformedEvent};
use sqlx::{Postgres, Transaction};
use tracing::info;

use crate::application::{AppError, NotificationRepositoryPort, NotificationService};
use crate::domain::OrderPlaced;

pub const TOPIC: &str = "orders.order-placed.v1";
pub const EVENT_TYPE: &str = "OrderPlaced";
/// Idempotency key namespace in `platform.processed_event`.
pub const CONSUMER_NAME: &str = "notify-svc";

/// Records one notification per `OrderPlaced` event.
///
/// Invalid `data` (missing/mistyped field, or rejected by the domain, e.g. a malformed total) can
/// never succeed, so it is returned as [`MalformedEvent`]: the runner logs it at WARN and commits
/// past it instead of retrying forever. Repository errors are transient and retried. Cancel-safe:
/// the only await is inside the runner's transaction, which is rolled back if dropped.
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
    serde_path_to_error::deserialize(data).map_err(|e| {
        let inner = e.inner().to_string();
        let path = e.path().to_string();
        if inner.starts_with("missing field") {
            MalformedEvent::new(format!("data: {inner}"))
        } else if path.is_empty() || path == "." {
            MalformedEvent::new("data is not an OrderPlaced object")
        } else {
            MalformedEvent::new(format!("data.{path} has an invalid value"))
        }
    })
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
            Err(e) => Err(anyhow::Error::new(e)),
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
}
