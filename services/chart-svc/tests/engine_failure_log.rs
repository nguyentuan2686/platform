//! An engine failure is logged exactly once at ERROR, without the birth date or time.
//!
//! Own test binary with exactly one test and a global subscriber (see the Rust conventions:
//! subscriber-dependent tests never share a binary).

use std::io::Write;
use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use chart_svc::app::{router, AppState};
use chart_svc::application::birth::NormalizedBirth;
use chart_svc::application::ports::ZiweiEngine;
use chart_svc::domain::chart::{Chart, Flow, Gender};
use platform_core::telemetry::JsonLayer;
use serde_json::{json, Value};
use tower::ServiceExt as _;
use tracing_subscriber::layer::SubscriberExt as _;

#[derive(Clone, Default)]
struct Buf(Arc<Mutex<Vec<u8>>>);

impl Write for Buf {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(b);
        Ok(b.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl tracing_subscriber::fmt::MakeWriter<'_> for Buf {
    type Writer = Self;

    fn make_writer(&self) -> Self {
        self.clone()
    }
}

struct BrokenEngine;

impl ZiweiEngine for BrokenEngine {
    fn chart(&self, _: &NormalizedBirth, _: Gender) -> anyhow::Result<Chart> {
        Err(anyhow::anyhow!("engine broke"))
    }

    fn flow(&self, _: &NormalizedBirth, _: Gender, _: time::Date) -> anyhow::Result<Flow> {
        Err(anyhow::anyhow!("engine broke"))
    }
}

#[tokio::test]
async fn engine_failure_is_logged_once_without_the_birth() {
    let buf = Buf::default();
    let subscriber = tracing_subscriber::registry().with(JsonLayer::new("chart-svc", buf.clone()));
    tracing::subscriber::set_global_default(subscriber).unwrap();

    let body = json!({"birth": "1994-03-12T08:30", "gender": "male"});
    let res = router(AppState::with_engine(BrokenEngine))
        .oneshot(
            Request::post("/tuvi/chart")
                .header("content-type", "application/json")
                .body(Body::from(serde_json::to_vec(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::INTERNAL_SERVER_ERROR);

    let logs = String::from_utf8(buf.0.lock().unwrap().clone()).unwrap();
    let errors: Vec<Value> = logs
        .lines()
        .map(|l| serde_json::from_str::<Value>(l).unwrap())
        .filter(|v| v["level"] == "ERROR")
        .collect();
    assert_eq!(errors.len(), 1, "{logs}");
    assert!(errors[0].to_string().contains("engine broke"), "{logs}");
    // The birth must not leak into any field. The log's own `timestamp` is left out: at 08:30 UTC (or with "1994" in
    // its nanoseconds) the wall clock would match the needle.
    let without_timestamps: Vec<String> = logs
        .lines()
        .map(|l| {
            let mut v = serde_json::from_str::<Value>(l).unwrap();
            v.as_object_mut().unwrap().remove("timestamp");
            v.to_string()
        })
        .collect();
    for needle in ["1994", "08:30"] {
        for line in &without_timestamps {
            assert!(!line.contains(needle), "log line contains {needle}: {line}");
        }
    }
}
