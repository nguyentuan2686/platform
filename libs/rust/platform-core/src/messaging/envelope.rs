//! The event envelope shared by all producers: `{event_id, event_type, occurred_at, data}`.

use serde::Deserialize;
use time::OffsetDateTime;
use uuid::Uuid;

/// Consumed event envelope. All four fields are required; unknown top-level fields are ignored and
/// `data` is kept as open JSON (v1 additive evolution rule).
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Envelope {
    pub event_id: Uuid,
    pub event_type: String,
    #[serde(with = "time::serde::rfc3339")]
    pub occurred_at: OffsetDateTime,
    pub data: serde_json::Value,
}

impl Envelope {
    /// Parses a Kafka message payload. The error message names the offending field but never
    /// contains the payload itself.
    pub fn parse(payload: &[u8]) -> Result<Self, serde_json::Error> {
        serde_json::from_slice(payload)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VALID: &str = r#"{
        "event_id": "6f1c1d1e-3b0a-4a8e-9a4e-2f1d7c3b5a10",
        "event_type": "OrderPlaced",
        "occurred_at": "2026-11-24T10:15:30.123456Z",
        "data": {"order_id": "9b2c", "customer_id": "c-1", "total": "12.50", "item_count": 2}
    }"#;

    #[test]
    fn parses_valid_envelope() {
        let e = Envelope::parse(VALID.as_bytes()).expect("valid");
        assert_eq!(
            e.event_id.to_string(),
            "6f1c1d1e-3b0a-4a8e-9a4e-2f1d7c3b5a10"
        );
        assert_eq!(e.event_type, "OrderPlaced");
        assert_eq!(e.occurred_at.unix_timestamp(), 1_795_515_330);
        assert_eq!(e.occurred_at.microsecond(), 123_456);
        assert_eq!(e.data["item_count"], 2);
    }

    #[test]
    fn missing_event_id_is_error() {
        let json = r#"{"event_type":"OrderPlaced","occurred_at":"2026-11-24T10:15:30Z","data":{}}"#;
        let err = Envelope::parse(json.as_bytes()).expect_err("missing event_id");
        assert!(err.to_string().contains("event_id"), "{err}");
    }

    #[test]
    fn each_of_the_four_fields_is_required() {
        for field in ["event_id", "event_type", "occurred_at", "data"] {
            let mut v: serde_json::Value = serde_json::from_str(VALID).expect("json");
            v.as_object_mut().expect("object").remove(field);
            let bytes = serde_json::to_vec(&v).expect("bytes");
            assert!(
                Envelope::parse(&bytes).is_err(),
                "missing {field} must fail"
            );
        }
    }

    #[test]
    fn not_json_and_bad_values_are_errors() {
        assert!(Envelope::parse(b"not json").is_err());
        assert!(Envelope::parse(b"").is_err());
        let bad_uuid = VALID.replace("6f1c1d1e-3b0a-4a8e-9a4e-2f1d7c3b5a10", "nope");
        assert!(Envelope::parse(bad_uuid.as_bytes()).is_err());
        let bad_time = VALID.replace("2026-11-24T10:15:30.123456Z", "yesterday");
        assert!(Envelope::parse(bad_time.as_bytes()).is_err());
    }

    #[test]
    fn extra_data_fields_are_kept() {
        let json = VALID.replace(r#""item_count": 2"#, r#""item_count": 2, "coupon": "X1""#);
        let e = Envelope::parse(json.as_bytes()).expect("valid");
        assert_eq!(e.data["coupon"], "X1");
    }

    #[test]
    fn extra_top_level_fields_are_ignored() {
        let json = VALID.replacen('{', r#"{"schema_version": 3, "source": "order-svc","#, 1);
        let e = Envelope::parse(json.as_bytes()).expect("valid");
        assert_eq!(e.event_type, "OrderPlaced");
    }
}
