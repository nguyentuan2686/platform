//! Separate test binary (ONE test): owns process-global env and subscriber. A collector that
//! accepts connections but never answers (blackhole) must not stall shutdown beyond ~5s.

use std::net::TcpListener;
use std::sync::mpsc;
use std::time::{Duration, Instant};

use platform_core::telemetry::init;

#[test]
fn blackholed_collector_does_not_stall_shutdown() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = listener.local_addr().unwrap().port();
    // Accept and hold connections open without ever replying.
    std::thread::spawn(move || {
        let mut held = Vec::new();
        for s in listener.incoming().flatten() {
            held.push(s);
        }
    });
    std::env::set_var(
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        format!("http://127.0.0.1:{port}"),
    );
    std::env::set_var("OTEL_EXPORTER_OTLP_TIMEOUT", "60000");
    let guard = init("blackhole").expect("init");
    let hist = opentelemetry::global::meter("platform-core")
        .f64_histogram("blackhole.test")
        .build();
    hist.record(0.1, &[]);
    tracing::info_span!("s").in_scope(|| tracing::info!("work"));

    let started = Instant::now();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        drop(guard);
        let _ = tx.send(());
    });
    rx.recv_timeout(Duration::from_secs(8))
        .expect("guard drop must be bounded");
    assert!(
        started.elapsed() < Duration::from_secs(6),
        "shutdown took {:?}",
        started.elapsed()
    );
}
