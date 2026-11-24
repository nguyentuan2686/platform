//! Shared fixtures for the HTTP-level tests: an in-memory repository and the app router over it.
#![allow(dead_code)] // each test binary uses a subset

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt as _;
use notify_svc::adapters::http::router;
use notify_svc::application::{
    Clock, IdGenerator, NotificationRepositoryPort, NotificationService,
};
use notify_svc::domain::{Notification, OrderPlaced};
use platform_core::http::ReadinessCheck;
use sqlx::{Postgres, Transaction};
use time::OffsetDateTime;
use tower::ServiceExt as _;
use uuid::Uuid;

/// In-memory repository; `list_recent` sorts newest first like the SQL adapter.
#[derive(Clone, Default)]
pub struct InMemoryRepo {
    pub rows: Arc<Mutex<Vec<Notification>>>,
    pub fail: bool,
}

#[async_trait]
impl NotificationRepositoryPort for InMemoryRepo {
    async fn save(
        &self,
        _: &mut Transaction<'_, Postgres>,
        n: &Notification,
    ) -> anyhow::Result<()> {
        self.rows.lock().unwrap().push(n.clone());
        Ok(())
    }

    async fn list_recent(&self, limit: u32) -> anyhow::Result<Vec<Notification>> {
        if self.fail {
            anyhow::bail!("connection refused to 10.0.0.1:5432");
        }
        let mut rows = self.rows.lock().unwrap().clone();
        rows.sort_by_key(|n| std::cmp::Reverse(n.created_at()));
        rows.truncate(limit as usize);
        Ok(rows)
    }
}

struct EpochClock;
impl Clock for EpochClock {
    fn now(&self) -> OffsetDateTime {
        OffsetDateTime::UNIX_EPOCH
    }
}
struct NewIds;
impl IdGenerator for NewIds {
    fn next_id(&self) -> Uuid {
        Uuid::new_v4()
    }
}

struct AlwaysReady;
#[async_trait]
impl ReadinessCheck for AlwaysReady {
    async fn ready(&self) -> bool {
        true
    }
}

/// A notification for `order` created `secs` seconds after the epoch.
pub fn notification(order: u128, secs: i64) -> Notification {
    let e = OrderPlaced {
        order_id: Uuid::from_u128(order),
        customer_id: format!("cust-{order}"),
        total: "25.50".into(),
        item_count: 2,
    };
    Notification::for_order_placed(
        &e,
        Uuid::new_v4(),
        OffsetDateTime::UNIX_EPOCH + time::Duration::seconds(secs),
    )
    .unwrap()
}

pub fn app(repo: InMemoryRepo) -> Router {
    let service = Arc::new(NotificationService::new(
        repo,
        Arc::new(EpochClock),
        Arc::new(NewIds),
    ));
    router(service, Arc::new(AlwaysReady))
}

pub struct Res {
    pub status: StatusCode,
    pub content_type: String,
    pub body: serde_json::Value,
}

pub async fn get(app: Router, uri: &str) -> Res {
    let res = app
        .oneshot(Request::get(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = res.status();
    let content_type = res
        .headers()
        .get("content-type")
        .map_or_default(|v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    let body = if bytes.is_empty() {
        serde_json::Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap()
    };
    Res {
        status,
        content_type,
        body,
    }
}
