//! Separate test binary: owns process-global env and subscriber, so it must keep exactly ONE test.
//! An unreachable collector must neither fail `init` nor hang shutdown (metrics flush on drop).

use std::sync::mpsc;
use std::time::Duration;

use platform_core::telemetry::init;

#[test]
fn unreachable_collector_does_not_break_init_or_hang_shutdown() {
    std::env::set_var("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:9");
    let guard = init("unreachable").expect("init must succeed with an unreachable collector");

    let hist = opentelemetry::global::meter("platform-core")
        .f64_histogram("unreachable.test")
        .build();
    hist.record(0.1, &[]);

    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        drop(guard);
        let _ = tx.send(());
    });
    rx.recv_timeout(Duration::from_secs(10))
        .expect("guard drop (flush + shutdown) must finish within 10s");
}
