//! An ephemeris failure is logged exactly once at ERROR with an `error_type`, and without the
//! birth data or coordinates.
//!
//! Own test binary with exactly one test and a global subscriber (see the Rust conventions:
//! subscriber-dependent tests never share a binary).

use std::io::Write;
use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use platform_core::telemetry::JsonLayer;
use serde_json::Value;
use tower::ServiceExt as _;
use tracing_subscriber::layer::SubscriberExt as _;

// The service crate in a group of its own: rustfmt sorts within a group, so the order of these
// lines does not depend on the rendered crate name.
use astro_chart_svc::app::{router, AppState};
use astro_chart_svc::application::ports::EphemerisPort;
use astro_chart_svc::config::DEFAULT_SERVICE_NAME;
use astro_chart_svc::domain::body::{Body as Planet, Ecliptic};
use astro_chart_svc::domain::time::Moment;

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

struct BrokenEphemeris;

impl EphemerisPort for BrokenEphemeris {
    fn apparent(&self, _: Planet, _: Moment) -> anyhow::Result<Ecliptic> {
        Err(anyhow::anyhow!("ephemeris broke"))
    }
}

#[tokio::test]
async fn ephemeris_failure_is_logged_once_without_the_birth() {
    let buf = Buf::default();
    let subscriber =
        tracing_subscriber::registry().with(JsonLayer::new(DEFAULT_SERVICE_NAME, buf.clone()));
    tracing::subscriber::set_global_default(subscriber).unwrap();

    let body = r#"{"birth":"1994-03-12T08:30","zone":"Asia/Ho_Chi_Minh","lat":21.0285,"lon":105.8542,"timeKnown":true}"#;
    let res = router(AppState::with_ephemeris(BrokenEphemeris))
        .oneshot(
            Request::post("/astro/chart")
                .header("content-type", "application/json")
                .body(Body::from(body))
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
    assert!(errors[0].to_string().contains("ephemeris broke"), "{logs}");
    // `error_type` is emitted by the platform JSON layer as `error.type`.
    assert_eq!(errors[0]["error"]["type"], "InternalError", "{logs}");
    for needle in ["1994", "08:30", "21.0285", "105.8542", "Ho_Chi_Minh"] {
        assert!(!logs.contains(needle), "logs contain {needle}: {logs}");
    }
}
