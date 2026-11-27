//! Startup wiring: pool → platform + service migrations → HTTP server and Kafka consumer.
//!
//! The HTTP port is bound only after the pool is connected and migrations are applied. The
//! consumer runs in its own task; Kafka failures are logged and retried and never stop the HTTP
//! server (Kafka down at startup still yields a live service). If the consumer task itself dies
//! (panic) before shutdown, the service shuts down and returns an error, so the process exits
//! non-zero and is restarted. Cancelling `shutdown` stops the consumer (bounded by
//! [`CONSUMER_STOP_TIMEOUT`]), drains HTTP and closes the pool; telemetry is flushed by `main`.

use std::future::{Future, IntoFuture as _};
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context as _;
use platform_core::messaging::{run_consumer, ConsumerConfig, EventHandler};
use sqlx::PgPool;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tracing::{error, info};

use crate::adapters::db::{self, NotificationRepositoryAdapter, PgReadiness};
use crate::adapters::http;
use crate::adapters::messaging::{OrderPlacedHandler, CONSUMER_NAME, TOPIC};
use crate::adapters::system::{RandomIds, SystemClock};
use crate::application::NotificationService;
use crate::config::Config;

/// Delay before re-creating the consumer after `run_consumer` returned an error.
const CONSUMER_RESTART_DELAY: Duration = Duration::from_secs(5);
/// A consumer run that lasted at least this long counts as healthy and resets the restart budget.
const CONSUMER_HEALTHY_RUN: Duration = Duration::from_secs(60);
/// Consecutive quick consumer failures (each run shorter than `CONSUMER_HEALTHY_RUN`) after which the service exits
/// non-zero instead of re-creating the client again, so Kubernetes restarts the pod and the restart is visible.
const CONSUMER_MAX_QUICK_FAILURES: u32 = 5;
/// Upper bound for the consumer to stop after shutdown is requested.
pub const CONSUMER_STOP_TIMEOUT: Duration = Duration::from_secs(10);

/// Connects and migrates the database, then binds `0.0.0.0:<PORT>` and runs the service until
/// `shutdown` is cancelled.
pub async fn run(config: Config, shutdown: CancellationToken) -> anyhow::Result<()> {
    let port = config.port;
    start(config, shutdown, async move {
        TcpListener::bind(("0.0.0.0", port))
            .await
            .with_context(|| format!("binding port {port}"))
    })
    .await
}

/// Runs the service on an already bound listener (tests use a random port).
pub async fn serve(
    config: Config,
    listener: TcpListener,
    shutdown: CancellationToken,
) -> anyhow::Result<()> {
    start(config, shutdown, async { Ok(listener) }).await
}

/// Pool → migrations → `bind` → HTTP + consumer.
async fn start(
    config: Config,
    shutdown: CancellationToken,
    bind: impl Future<Output = anyhow::Result<TcpListener>>,
) -> anyhow::Result<()> {
    let pool = db::connect(&config.db_url).await?;
    db::migrate(&pool).await?;
    let listener = bind.await?;

    let service = Arc::new(NotificationService::new(
        NotificationRepositoryAdapter::new(pool.clone()),
        Arc::new(SystemClock),
        Arc::new(RandomIds),
    ));
    let handler: Arc<dyn EventHandler> = Arc::new(OrderPlacedHandler::new(service.clone()));
    let consumer_cfg = ConsumerConfig::new(
        config.kafka_bootstrap_servers.clone(),
        config.kafka_group_id.clone(),
        TOPIC,
        CONSUMER_NAME,
    );
    let consumer = tokio::spawn(consume(
        consumer_cfg,
        pool.clone(),
        handler,
        shutdown.clone(),
    ));

    let router = http::router(service, Arc::new(PgReadiness::new(pool.clone())));
    info!(addr = %listener.local_addr()?, "http server listening");
    let server = axum::serve(listener, router)
        .with_graceful_shutdown(shutdown.clone().cancelled_owned())
        .into_future();

    let result = supervise(server, consumer, &shutdown, CONSUMER_STOP_TIMEOUT).await;
    pool.close().await;
    info!("shutdown complete");
    result
}

/// Runs `server` until it ends or the consumer task ends. A consumer that ends before shutdown
/// was requested (panic or error) is logged at ERROR, cancels `shutdown` so the server drains,
/// and makes this return an error. On shutdown the consumer gets `consumer_timeout` to stop.
async fn supervise(
    server: impl Future<Output = std::io::Result<()>>,
    mut consumer: JoinHandle<anyhow::Result<()>>,
    shutdown: &CancellationToken,
    consumer_timeout: Duration,
) -> anyhow::Result<()> {
    tokio::pin!(server);
    let mut consumer_failed = false;
    let http_result = tokio::select! {
        result = &mut server => {
            shutdown.cancel();
            stop_consumer(&mut consumer, consumer_timeout).await;
            result
        }
        joined = &mut consumer => {
            let unexpected = !shutdown.is_cancelled();
            shutdown.cancel();
            let failure = match joined {
                Ok(Ok(())) if !unexpected => None,
                Ok(Ok(())) => Some("kafka consumer stopped without a shutdown request".to_owned()),
                Ok(Err(e)) => Some(format!("{e:#}")),
                Err(e) => Some(e.to_string()),
            };
            if let Some(reason) = failure {
                error!(
                    error = %reason,
                    error_type = "ConsumerTaskFailed",
                    "kafka consumer task ended with a failure; shutting down"
                );
                consumer_failed = unexpected;
            }
            server.await
        }
    };
    if consumer_failed {
        anyhow::bail!("kafka consumer task ended unexpectedly");
    }
    http_result.context("http server")
}

/// Awaits the (already cancelled) consumer for at most `limit`; a panic or timeout is logged.
async fn stop_consumer(consumer: &mut JoinHandle<anyhow::Result<()>>, limit: Duration) {
    match tokio::time::timeout(limit, &mut *consumer).await {
        Ok(Ok(Ok(()))) => {}
        Ok(Ok(Err(e))) => {
            error!(error = %format!("{e:#}"), error_type = "ConsumerTaskFailed", "kafka consumer failed during shutdown");
        }
        Ok(Err(e)) => {
            error!(error = %e, error_type = "ConsumerTaskPanicked", "kafka consumer task failed");
        }
        Err(_) => {
            consumer.abort();
            error!(
                error_type = "ConsumerStopTimeout",
                timeout_ms = u64::try_from(limit.as_millis()).unwrap_or(u64::MAX),
                "kafka consumer did not stop in time; abandoned"
            );
        }
    }
}

/// Runs the consumer until cancelled, re-creating it if the client cannot be set up.
/// Returns an error only if `run_consumer` stops although `shutdown` was not cancelled.
async fn consume(
    cfg: ConsumerConfig,
    pool: PgPool,
    handler: Arc<dyn EventHandler>,
    shutdown: CancellationToken,
) -> anyhow::Result<()> {
    let mut budget = RestartBudget::default();
    loop {
        let started = std::time::Instant::now();
        match run_consumer(cfg.clone(), pool.clone(), handler.clone(), shutdown.clone()).await {
            Ok(()) if shutdown.is_cancelled() => return Ok(()),
            Ok(()) => anyhow::bail!("kafka consumer stopped without a shutdown request"),
            Err(e) if budget.exhausted_after(started.elapsed()) => {
                return Err(e.context(format!(
                    "kafka consumer failed {CONSUMER_MAX_QUICK_FAILURES} times in a row, each within {}s",
                    CONSUMER_HEALTHY_RUN.as_secs()
                )));
            }
            Err(e) => {
                error!(
                    error = %format!("{e:#}"),
                    error_type = "KafkaConsumerFailed",
                    topic = %cfg.topic,
                    "kafka consumer failed; restarting"
                );
                tokio::select! {
                    () = shutdown.cancelled() => return Ok(()),
                    () = tokio::time::sleep(CONSUMER_RESTART_DELAY) => {}
                }
            }
        }
    }
}

/// Counts consecutive quick consumer failures; a long healthy run starts the count again.
#[derive(Debug, Default)]
struct RestartBudget {
    quick_failures: u32,
}

impl RestartBudget {
    /// Records a failed run that lasted `ran_for`; true once the budget is used up.
    fn exhausted_after(&mut self, ran_for: Duration) -> bool {
        if ran_for >= CONSUMER_HEALTHY_RUN {
            self.quick_failures = 0;
        }
        self.quick_failures += 1;
        self.quick_failures >= CONSUMER_MAX_QUICK_FAILURES
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restart_budget_is_exhausted_by_consecutive_quick_failures() {
        let mut b = RestartBudget::default();
        for i in 1..CONSUMER_MAX_QUICK_FAILURES {
            assert!(
                !b.exhausted_after(Duration::from_secs(1)),
                "failure {i} still re-creates the consumer"
            );
        }
        assert!(b.exhausted_after(Duration::from_secs(1)));
    }

    #[test]
    fn restart_budget_resets_after_a_healthy_run() {
        let mut b = RestartBudget::default();
        for _ in 1..CONSUMER_MAX_QUICK_FAILURES {
            assert!(!b.exhausted_after(Duration::from_secs(1)));
        }
        assert!(
            !b.exhausted_after(CONSUMER_HEALTHY_RUN),
            "a long run starts the count again"
        );
        assert!(!b.exhausted_after(Duration::from_secs(1)));
    }

    /// Fake HTTP server: runs until shutdown, like `axum::serve` with graceful shutdown.
    fn server(shutdown: &CancellationToken) -> impl Future<Output = std::io::Result<()>> {
        let token = shutdown.clone();
        async move {
            token.cancelled().await;
            Ok(())
        }
    }

    /// Fake consumer: stops on shutdown.
    fn polite_consumer(shutdown: &CancellationToken) -> JoinHandle<anyhow::Result<()>> {
        let token = shutdown.clone();
        tokio::spawn(async move {
            token.cancelled().await;
            Ok(())
        })
    }

    const TIMEOUT: Duration = Duration::from_millis(200);

    #[tokio::test]
    async fn consumer_panic_before_shutdown_stops_service_with_error() {
        let shutdown = CancellationToken::new();
        let consumer = tokio::spawn(async { panic!("boom") });
        let res = tokio::time::timeout(
            Duration::from_secs(5),
            supervise(server(&shutdown), consumer, &shutdown, TIMEOUT),
        )
        .await
        .expect("supervise must not wait for an external shutdown");
        assert!(res.is_err());
        assert!(shutdown.is_cancelled(), "server is drained via shutdown");
    }

    #[tokio::test]
    async fn consumer_error_before_shutdown_stops_service_with_error() {
        let shutdown = CancellationToken::new();
        let consumer = tokio::spawn(async { anyhow::bail!("gave up") });
        let res = tokio::time::timeout(
            Duration::from_secs(5),
            supervise(server(&shutdown), consumer, &shutdown, TIMEOUT),
        )
        .await
        .expect("supervise must not wait for an external shutdown");
        assert!(res.is_err());
    }

    #[tokio::test]
    async fn requested_shutdown_is_clean() {
        let shutdown = CancellationToken::new();
        let consumer = polite_consumer(&shutdown);
        shutdown.cancel();
        let res = supervise(server(&shutdown), consumer, &shutdown, TIMEOUT).await;
        assert!(res.is_ok(), "{res:?}");
    }

    #[tokio::test]
    async fn server_error_cancels_consumer_and_is_returned() {
        let shutdown = CancellationToken::new();
        let consumer = polite_consumer(&shutdown);
        let failing = async { Err(std::io::Error::other("accept failed")) };
        let res = supervise(failing, consumer, &shutdown, TIMEOUT).await;
        assert!(res.is_err());
        assert!(shutdown.is_cancelled());
    }

    #[tokio::test]
    async fn hung_consumer_is_abandoned_after_timeout() {
        let shutdown = CancellationToken::new();
        let consumer = tokio::spawn(std::future::pending::<anyhow::Result<()>>());
        shutdown.cancel();
        let res = tokio::time::timeout(
            Duration::from_secs(5),
            supervise(server(&shutdown), consumer, &shutdown, TIMEOUT),
        )
        .await
        .expect("consumer wait must be bounded");
        assert!(res.is_ok(), "a slow stop is logged, not fatal: {res:?}");
    }
}
