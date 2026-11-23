//! HTTP adapter tests over an in-memory repository (`tower::ServiceExt::oneshot`).

mod common;

use axum::http::StatusCode;
use common::{app, get, notification, InMemoryRepo};
use uuid::Uuid;

fn repo_with(rows: Vec<notify_svc::domain::Notification>) -> InMemoryRepo {
    let repo = InMemoryRepo::default();
    *repo.rows.lock().unwrap() = rows;
    repo
}

#[tokio::test]
async fn list_returns_newest_first_with_camel_case_fields() {
    let repo = repo_with(vec![
        notification(1, 10),
        notification(2, 30),
        notification(3, 20),
    ]);
    let res = get(app(repo), "/notifications").await;

    assert_eq!(res.status, StatusCode::OK);
    assert!(
        res.content_type.starts_with("application/json"),
        "{}",
        res.content_type
    );
    let items = res.body.as_array().expect("array");
    let orders: Vec<&str> = items
        .iter()
        .map(|n| n["orderId"].as_str().unwrap())
        .collect();
    assert_eq!(
        orders,
        [2u128, 3, 1].map(|o| Uuid::from_u128(o).to_string())
    );

    let first = items[0].as_object().unwrap();
    let mut keys: Vec<&str> = first.keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        [
            "channel",
            "createdAt",
            "customerId",
            "id",
            "message",
            "orderId"
        ]
    );
    assert_eq!(first["customerId"], "cust-2");
    assert_eq!(first["channel"], "log");
    assert_eq!(first["createdAt"], "1970-01-01T00:00:30Z");
    assert_eq!(
        first["message"],
        format!(
            "Order {} placed: 2 item(s), total 25.50",
            Uuid::from_u128(2)
        )
    );
}

#[tokio::test]
async fn limit_bounds_the_result() {
    let rows = (0..5)
        .map(|i| notification(i, i64::try_from(i).unwrap()))
        .collect();
    let repo = repo_with(rows);
    for (uri, expected) in [
        ("/notifications?limit=2", 2),
        ("/notifications?limit=1", 1),
        ("/notifications?limit=200", 5),
        ("/notifications", 5),
    ] {
        let res = get(app(repo.clone()), uri).await;
        assert_eq!(res.status, StatusCode::OK, "{uri}");
        assert_eq!(res.body.as_array().unwrap().len(), expected, "{uri}");
    }
}

#[tokio::test]
async fn empty_list_is_ok() {
    let res = get(app(InMemoryRepo::default()), "/notifications").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.body, serde_json::json!([]));
}

#[tokio::test]
async fn invalid_limit_is_400_problem_on_field_limit() {
    for bad in ["0", "201", "-1", "abc", "1.5", "", "99999999999999999999"] {
        let uri = format!("/notifications?limit={bad}");
        let res = get(app(InMemoryRepo::default()), &uri).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "limit={bad}");
        assert_eq!(res.content_type, "application/problem+json", "limit={bad}");
        assert_eq!(
            res.body["type"], "urn:problem:validation-error",
            "limit={bad}"
        );
        assert_eq!(res.body["status"], 400);
        assert_eq!(
            res.body["errors"][0]["field"], "limit",
            "limit={bad}: {}",
            res.body
        );
        assert!(res.body["trace_id"].is_string());
    }
}

#[tokio::test]
async fn repository_failure_is_500_without_internals() {
    let repo = InMemoryRepo {
        fail: true,
        ..Default::default()
    };
    let res = get(app(repo), "/notifications").await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(res.content_type, "application/problem+json");
    assert_eq!(res.body["type"], "urn:problem:internal-error");
    assert_eq!(res.body["detail"], "Unexpected error");
    assert!(!res.body.to_string().contains("10.0.0.1"));
}

#[tokio::test]
async fn health_endpoints_are_mounted() {
    let live = get(app(InMemoryRepo::default()), "/health/live").await;
    assert_eq!(live.status, StatusCode::OK);
    assert_eq!(live.body, serde_json::json!({"status": "UP"}));
    let ready = get(app(InMemoryRepo::default()), "/health/ready").await;
    assert_eq!(ready.status, StatusCode::OK);
}
