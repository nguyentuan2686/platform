use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Channel {
    Log,
}

impl Channel {
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Channel::Log => "log",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DomainError {
    #[error("customer id must not be blank")]
    BlankCustomerId,
    #[error("item count must be at least 1")]
    ZeroItemCount,
    #[error("total must match ^[0-9]+\\.[0-9]{{2}}$")]
    InvalidTotal,
}

/// Application input, parsed from the `OrderPlaced` event payload.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct OrderPlaced {
    pub order_id: Uuid,
    pub customer_id: String,
    pub total: String,
    pub item_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Notification {
    id: Uuid,
    order_id: Uuid,
    customer_id: String,
    channel: Channel,
    message: String,
    created_at: OffsetDateTime,
}

/// Hand-checked `^[0-9]+\.[0-9]{2}$`.
fn valid_total(s: &str) -> bool {
    match s.split_once('.') {
        Some((int, frac)) => {
            !int.is_empty()
                && int.bytes().all(|b| b.is_ascii_digit())
                && frac.len() == 2
                && frac.bytes().all(|b| b.is_ascii_digit())
        }
        None => false,
    }
}

impl Notification {
    pub fn for_order_placed(
        e: &OrderPlaced,
        id: Uuid,
        now: OffsetDateTime,
    ) -> Result<Self, DomainError> {
        if e.customer_id.trim().is_empty() {
            return Err(DomainError::BlankCustomerId);
        }
        if e.item_count == 0 {
            return Err(DomainError::ZeroItemCount);
        }
        if !valid_total(&e.total) {
            return Err(DomainError::InvalidTotal);
        }
        Ok(Self {
            id,
            order_id: e.order_id,
            customer_id: e.customer_id.clone(),
            channel: Channel::Log,
            message: format!(
                "Order {} placed: {} item(s), total {}",
                e.order_id, e.item_count, e.total
            ),
            created_at: now,
        })
    }

    /// Rehydrate from storage without re-validating.
    #[must_use]
    pub fn restore(
        id: Uuid,
        order_id: Uuid,
        customer_id: String,
        channel: Channel,
        message: String,
        created_at: OffsetDateTime,
    ) -> Self {
        Self {
            id,
            order_id,
            customer_id,
            channel,
            message,
            created_at,
        }
    }

    #[must_use]
    pub fn id(&self) -> Uuid {
        self.id
    }
    #[must_use]
    pub fn order_id(&self) -> Uuid {
        self.order_id
    }
    #[must_use]
    pub fn customer_id(&self) -> &str {
        &self.customer_id
    }
    #[must_use]
    pub fn channel(&self) -> Channel {
        self.channel
    }
    #[must_use]
    pub fn message(&self) -> &str {
        &self.message
    }
    #[must_use]
    pub fn created_at(&self) -> OffsetDateTime {
        self.created_at
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event() -> OrderPlaced {
        OrderPlaced {
            order_id: Uuid::from_u128(7),
            customer_id: "c-1".into(),
            total: "25.50".into(),
            item_count: 3,
        }
    }

    fn make(e: &OrderPlaced) -> Result<Notification, DomainError> {
        Notification::for_order_placed(e, Uuid::from_u128(1), OffsetDateTime::UNIX_EPOCH)
    }

    #[test]
    fn message_is_exact() {
        let n = make(&event()).unwrap();
        assert_eq!(
            n.message(),
            format!(
                "Order {} placed: 3 item(s), total 25.50",
                Uuid::from_u128(7)
            )
        );
        assert_eq!(n.channel(), Channel::Log);
        assert_eq!(n.id(), Uuid::from_u128(1));
        assert_eq!(n.order_id(), Uuid::from_u128(7));
        assert_eq!(n.customer_id(), "c-1");
        assert_eq!(n.created_at(), OffsetDateTime::UNIX_EPOCH);
    }

    #[test]
    fn rejects_blank_customer() {
        for c in ["", "   "] {
            let e = OrderPlaced {
                customer_id: c.into(),
                ..event()
            };
            assert_eq!(make(&e), Err(DomainError::BlankCustomerId));
        }
    }

    #[test]
    fn rejects_zero_items() {
        let e = OrderPlaced {
            item_count: 0,
            ..event()
        };
        assert_eq!(make(&e), Err(DomainError::ZeroItemCount));
    }

    #[test]
    fn rejects_bad_totals() {
        for t in [
            "", "25", "25.5", "25.500", ".50", "-1.00", "a.00", "1.0x", "1,00", " 1.00", "1.00\n",
        ] {
            let e = OrderPlaced {
                total: t.into(),
                ..event()
            };
            assert_eq!(make(&e), Err(DomainError::InvalidTotal), "total {t:?}");
        }
    }

    #[test]
    fn accepts_good_totals() {
        for t in ["0.00", "25.50", "1000000.99"] {
            let e = OrderPlaced {
                total: t.into(),
                ..event()
            };
            assert!(make(&e).is_ok(), "total {t:?}");
        }
    }

    #[test]
    fn channel_log_name() {
        assert_eq!(Channel::Log.as_str(), "log");
        let d = serde::de::value::StrDeserializer::<serde::de::value::Error>::new("log");
        assert_eq!(Channel::deserialize(d).unwrap(), Channel::Log);
    }
}
