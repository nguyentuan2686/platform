//! Contract tests: real HTTP responses against `contracts/openapi/notify-svc.yaml`, and an
//! order-svc-shaped event against `contracts/events/order-placed.v1.schema.json`, and a payment-svc-shaped one against
//! `contracts/events/payment-succeeded.v1.schema.json`.

mod common;

use std::path::PathBuf;

use axum::http::StatusCode;
use common::{app, get, notification, InMemoryRepo};
use jsonschema::Validator;
use notify_svc::domain::{OrderPlaced, PaymentSucceeded, TopUp};
use platform_core::messaging::Envelope;
use serde_json::{json, Value};

fn contracts() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../contracts")
}

fn openapi() -> Value {
    let text = std::fs::read_to_string(contracts().join("openapi/notify-svc.yaml")).unwrap();
    yaml_serde::from_str(&text).unwrap()
}

/// Validator for the schema at JSON pointer `pointer` inside the `OpenAPI` document. The whole
/// document is the root resource so `$ref: '#/components/...'` resolves; the extra top-level
/// `OpenAPI` keys are not JSON Schema keywords and are ignored.
fn openapi_validator(pointer: &str) -> Validator {
    let mut doc = openapi();
    assert!(doc.pointer(pointer).is_some(), "no schema at {pointer}");
    doc.as_object_mut()
        .unwrap()
        .insert("$ref".into(), Value::String(format!("#{pointer}")));
    jsonschema::options()
        .should_validate_formats(true)
        .build(&doc)
        .unwrap()
}

fn assert_valid(v: &Validator, instance: &Value) {
    let errors: Vec<String> = v.iter_errors(instance).map(|e| e.to_string()).collect();
    assert!(errors.is_empty(), "{instance} violates schema: {errors:?}");
}

const LIST_200: &str = "/paths/~1notifications/get/responses/200/content/application~1json/schema";
const LIST_400: &str =
    "/paths/~1notifications/get/responses/400/content/application~1problem+json/schema";
const LIST_500: &str =
    "/paths/~1notifications/get/responses/500/content/application~1problem+json/schema";

#[tokio::test]
async fn list_response_matches_openapi() {
    let repo = InMemoryRepo::default();
    *repo.rows.lock().unwrap() = vec![notification(1, 1), notification(2, 2)];
    let res = get(app(repo), "/notifications?limit=10").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.body.as_array().unwrap().len(), 2);
    assert_valid(&openapi_validator(LIST_200), &res.body);
}

#[tokio::test]
async fn problems_match_openapi() {
    let res = get(app(InMemoryRepo::default()), "/notifications?limit=0").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_valid(&openapi_validator(LIST_400), &res.body);

    let failing = InMemoryRepo {
        fail: true,
        ..Default::default()
    };
    let res = get(app(failing), "/notifications").await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_valid(&openapi_validator(LIST_500), &res.body);
}

#[test]
fn openapi_schemas_reject_wrong_shapes() {
    // Guards against a validator that accepts everything (e.g. an unresolved $ref).
    let list = openapi_validator(LIST_200);
    assert!(!list.is_valid(&json!([{"order_id": "x"}])));
    assert!(!list.is_valid(&json!({})));
    let problem = openapi_validator(LIST_400);
    assert!(!problem.is_valid(&json!({"title": "Bad Request"})));
}

#[test]
fn openapi_limit_parameter_is_bounded_1_to_200() {
    let doc = openapi();
    let schema = &doc["paths"]["/notifications"]["get"]["parameters"][0]["schema"];
    assert_eq!(
        doc["paths"]["/notifications"]["get"]["parameters"][0]["name"],
        "limit"
    );
    assert_eq!(schema["minimum"], 1);
    assert_eq!(schema["maximum"], 200);
}

fn event_schema() -> Validator {
    let text =
        std::fs::read_to_string(contracts().join("events/order-placed.v1.schema.json")).unwrap();
    let schema: Value = serde_json::from_str(&text).unwrap();
    jsonschema::options()
        .should_validate_formats(true)
        .build(&schema)
        .unwrap()
}

/// Built exactly like order-svc: `OutboxOrderEventsAdapter` (data) + `OutboxRelay.envelope`
/// (`occurred_at` = `Instant.toString()`, `total` = `BigDecimal.toPlainString()`).
fn order_svc_envelope() -> Value {
    json!({
        "event_id": "1b4e28ba-2fa1-4d2b-9f3c-0f6a8e2b7c11",
        "event_type": "OrderPlaced",
        "occurred_at": "2026-11-24T10:15:30.123456789Z",
        "data": {
            "order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d",
            "customer_id": "cust-42",
            "total": "25.50",
            "item_count": 3
        }
    })
}

#[test]
fn order_svc_event_matches_schema_and_parses() {
    let event = order_svc_envelope();
    assert_valid(&event_schema(), &event);

    let envelope = Envelope::parse(&serde_json::to_vec(&event).unwrap()).unwrap();
    assert_eq!(envelope.event_type, "OrderPlaced");
    let data: OrderPlaced = serde_json::from_value(envelope.data).unwrap();
    assert_eq!(
        data.order_id.to_string(),
        "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d"
    );
    assert_eq!(data.customer_id, "cust-42");
    assert_eq!(data.total, "25.50");
    assert_eq!(data.item_count, 3);
}

#[test]
fn additive_data_fields_stay_valid_and_are_ignored() {
    let mut event = order_svc_envelope();
    event["data"]["coupon"] = json!("WINTER");
    assert_valid(&event_schema(), &event);
    let envelope = Envelope::parse(&serde_json::to_vec(&event).unwrap()).unwrap();
    let data: OrderPlaced = serde_json::from_value(envelope.data).unwrap();
    assert_eq!(data.item_count, 3);
}

#[test]
fn event_schema_rejects_missing_required_data() {
    let mut event = order_svc_envelope();
    event["data"].as_object_mut().unwrap().remove("total");
    assert!(!event_schema().is_valid(&event));
}

fn payment_schema() -> Value {
    let text = std::fs::read_to_string(contracts().join("events/payment-succeeded.v1.schema.json"))
        .unwrap();
    serde_json::from_str(&text).unwrap()
}

/// As payment-svc publishes it (`Instant.toString()` timestamps, `BigDecimal.toPlainString()` amount).
fn payment_svc_envelope() -> Value {
    json!({
        "event_id": "2b4e28ba-2fa1-4d2b-9f3c-0f6a8e2b7c11",
        "event_type": "PaymentSucceeded",
        "occurred_at": "2026-11-28T07:05:09.123456Z",
        "data": {
            "order_id": "9a7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d",
            "order_code": "TV7K2M",
            "account_id": "3c7d5c3e-6b1f-4e2a-8c0d-3f5e7a9b1c2d",
            "product_code": "CREDIT_5",
            "site": "tuvi",
            "amount": "50000.00",
            "currency": "VND",
            "provider": "SEPAY",
            "paid_at": "2026-11-28T07:05:09.123456Z",
            "chart_profile_id": null
        }
    })
}

#[test]
fn payment_svc_event_matches_schema_and_becomes_the_topup_message() {
    let event = payment_svc_envelope();
    let validator = jsonschema::options()
        .should_validate_formats(true)
        .build(&payment_schema())
        .unwrap();
    assert_valid(&validator, &event);

    let envelope = Envelope::parse(&serde_json::to_vec(&event).unwrap()).unwrap();
    assert_eq!(envelope.event_type, "PaymentSucceeded");
    let data: PaymentSucceeded = serde_json::from_value(envelope.data).unwrap();
    let text = TopUp::from_event(&data).unwrap().message();
    assert_eq!(
        text,
        "💰 Nạp tiền\nGói: CREDIT_5 (5 lượt)\nSố tiền: 50.000đ\nLúc: 14:05 28/11"
    );
    for secret in ["TV7K2M", "3c7d5c3e", "9a7d5c3e", "SEPAY"] {
        assert!(!text.contains(secret), "{secret} in {text}");
    }
}

#[test]
fn every_schema_product_code_has_a_vietnamese_label() {
    let schema = payment_schema();
    let codes = schema
        .pointer("/properties/data/properties/product_code/enum")
        .unwrap()
        .as_array()
        .unwrap();
    for code in codes {
        let code = code.as_str().unwrap();
        assert_ne!(
            notify_svc::domain::topup::package_label(code),
            code,
            "{code} has no label"
        );
    }
}
