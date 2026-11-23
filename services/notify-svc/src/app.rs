//! Startup wiring: pool → platform + service migrations → HTTP server and Kafka consumer.
//!
//! The consumer runs in its own task; its failures are logged and retried and never stop the HTTP
//! server (Kafka down at startup still yields a live service). Cancelling `shutdown` stops the
//! consumer, drains HTTP and closes the pool; telemetry is flushed by the caller (`main`).

use std::sync::Arc;
use std::time::Duration;

use anyhow::Context as _;
use platform_core::messaging::{run_consumer, ConsumerConfig, EventHandler};
use sqlx::PgPool;
use tokio::net::TcpListener;
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

/// Binds `0.0.0.0:<PORT>` and runs the service until `shutdown` is cancelled.
pub async fn run(config: Config, shutdown: CancellationToken) -> anyhow::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", config.port))
        .await
        .with_context(|| format!("binding port {}", config.port))?;
    serve(config, listener, shutdown).await
}

/// Runs the service on an already bound listener (tests use a random port).
pub async fn serve(
    config: Config,
    listener: TcpListener,
    shutdown: CancellationToken,
) -> anyhow::Result<()> {
    let pool = db::connect(&config.db_url).await?;
    db::migrate(&pool).await?;

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
    let served = axum::serve(listener, router)
        .with_graceful_shutdown(shutdown.clone().cancelled_owned())
        .await;

    // HTTP may also end on its own (error); either way stop the consumer.
    shutdown.cancel();
    if let Err(e) = consumer.await {
        error!(error = %e, error_type = "ConsumerTaskPanicked", "kafka consumer task failed");
    }
    pool.close().await;
    info!("shutdown complete");
    served.context("http server")
}

/// Runs the consumer until cancelled, re-creating it if the client cannot be set up.
async fn consume(
    cfg: ConsumerConfig,
    pool: PgPool,
    handler: Arc<dyn EventHandler>,
    shutdown: CancellationToken,
) {
    loop {
        match run_consumer(cfg.clone(), pool.clone(), handler.clone(), shutdown.clone()).await {
            Ok(()) => return,
            Err(e) => {
                error!(
                    error = %format!("{e:#}"),
                    error_type = "KafkaConsumerFailed",
                    topic = %cfg.topic,
                    "kafka consumer failed; restarting"
                );
                tokio::select! {
                    () = shutdown.cancelled() => return,
                    () = tokio::time::sleep(CONSUMER_RESTART_DELAY) => {}
                }
            }
        }
    }
}
