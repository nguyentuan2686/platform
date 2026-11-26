//! Integration tests for the idempotent Kafka consumer runner (Postgres 17 + Redpanda).
//!
//! All scenarios share one Postgres and one Redpanda container (weak dev machine) and run
//! sequentially inside a single test so the containers are started once and removed at the end
//! (testcontainers-rs has no reaper; containers held in statics would leak). Each scenario gets its
//! own database, topic and consumer group, and failures are reported per scenario by name.

use std::borrow::Cow;
use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{ensure, Context as _};
use async_trait::async_trait;
use opentelemetry::trace::{SpanKind, TracerProvider as _};
use opentelemetry_sdk::metrics::data::{AggregatedMetrics, MetricData};
use opentelemetry_sdk::metrics::{InMemoryMetricExporter, PeriodicReader, SdkMeterProvider};
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::{InMemorySpanExporter, SdkTracerProvider};
use platform_core::db::migrate_platform;
use platform_core::messaging::{
    run_consumer, ConsumerConfig, Envelope, EventHandler, MalformedEvent,
};
use rdkafka::admin::{AdminClient, AdminOptions, NewTopic, TopicReplication};
use rdkafka::client::DefaultClientContext;
use rdkafka::consumer::{BaseConsumer, Consumer as _};
use rdkafka::message::{Header, OwnedHeaders};
use rdkafka::producer::{FutureProducer, FutureRecord};
use rdkafka::{ClientConfig, Offset, TopicPartitionList};
use sqlx::postgres::PgPoolOptions;
use sqlx::{PgPool, Postgres, Transaction};
use testcontainers::core::{ContainerPort, ContainerState, ExecCommand, WaitFor};
use testcontainers::runners::AsyncRunner;
use testcontainers::{ContainerAsync, Image, ImageExt, TestcontainersError};
use testcontainers_modules::postgres::Postgres as PostgresImage;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tracing::instrument::WithSubscriber;
use tracing_subscriber::layer::SubscriberExt;
use uuid::Uuid;

const EVENT_TYPE: &str = "OrderPlaced";
const WAIT: Duration = Duration::from_secs(60);
const TRACE_ID: &str = "4bf92f3577b34da6a3ce929d0e0e4736";
const PARENT_SPAN_ID: &str = "00f067aa0ba902b7";

// ---------------------------------------------------------------------------------------------
// Redpanda image: the advertised Kafka address must contain the mapped host port, which is only
// known after start, so the container waits for a start script written by `exec_after_start`.
// ---------------------------------------------------------------------------------------------

const KAFKA_PORT: ContainerPort = ContainerPort::Tcp(9092);

#[derive(Debug, Clone, Default)]
struct Redpanda;

#[allow(clippy::unnecessary_literal_bound)] // signatures fixed by the `Image` trait
impl Image for Redpanda {
    fn name(&self) -> &str {
        "docker.redpanda.com/redpandadata/redpanda"
    }

    fn tag(&self) -> &str {
        "v26.1.1"
    }

    fn ready_conditions(&self) -> Vec<WaitFor> {
        vec![WaitFor::message_on_stdout("waiting for start script")]
    }

    fn entrypoint(&self) -> Option<&str> {
        Some("/bin/sh")
    }

    fn cmd(&self) -> impl IntoIterator<Item = impl Into<Cow<'_, str>>> {
        [
            "-c",
            "echo 'waiting for start script'; \
             while [ ! -f /tmp/start.sh ]; do sleep 0.1; done; exec sh /tmp/start.sh",
        ]
    }

    fn expose_ports(&self) -> &[ContainerPort] {
        &[KAFKA_PORT]
    }

    fn exec_after_start(
        &self,
        cs: ContainerState,
    ) -> Result<Vec<ExecCommand>, TestcontainersError> {
        let advertised = format!("{}:{}", cs.host(), cs.host_port_ipv4(KAFKA_PORT)?);
        let script = format!(
            "exec /entrypoint.sh redpanda start --smp 1 --memory 512M --overprovisioned \
             --check=false --node-id 0 --kafka-addr PLAINTEXT://0.0.0.0:9092 \
             --advertise-kafka-addr PLAINTEXT://{advertised}"
        );
        let write = format!("echo '{script}' > /tmp/start.tmp && mv /tmp/start.tmp /tmp/start.sh");
        Ok(vec![ExecCommand::new(["sh", "-c", write.as_str()])
            .with_container_ready_conditions(vec![
                WaitFor::message_on_stderr("Successfully started Redpanda!"),
            ])])
    }
}

// ---------------------------------------------------------------------------------------------
// Shared environment and helpers
// ---------------------------------------------------------------------------------------------

struct Env {
    pg: ContainerAsync<PostgresImage>,
    pg_base: String,
    kafka: String,
    _redpanda: ContainerAsync<Redpanda>,
}

impl Env {
    async fn start() -> anyhow::Result<Self> {
        let (pg, redpanda) = tokio::try_join!(
            PostgresImage::default().with_tag("17-alpine").start(),
            Redpanda.start()
        )?;
        let pg_base = format!(
            "postgres://postgres:postgres@{}:{}",
            pg.get_host().await?,
            pg.get_host_port_ipv4(5432).await?
        );
        let kafka = format!(
            "{}:{}",
            redpanda.get_host().await?,
            redpanda.get_host_port_ipv4(KAFKA_PORT).await?
        );
        Ok(Self {
            pg,
            pg_base,
            kafka,
            _redpanda: redpanda,
        })
    }

    /// Fresh database with platform migrations applied and a `handled` table for the test handler.
    /// URL of a freshly created, empty database.
    async fn empty_db_url(&self) -> anyhow::Result<String> {
        let name = format!("t_{}", Uuid::new_v4().simple());
        let admin = PgPool::connect(&format!("{}/postgres", self.pg_base)).await?;
        sqlx::query(sqlx::AssertSqlSafe(format!("CREATE DATABASE {name}")))
            .execute(&admin)
            .await?;
        admin.close().await;
        Ok(format!("{}/{name}", self.pg_base))
    }

    async fn new_db(&self, opts: PgPoolOptions) -> anyhow::Result<PgPool> {
        let pool = opts.connect(&self.empty_db_url().await?).await?;
        migrate_platform(&pool).await?;
        // Applying twice must be a no-op (tracked in its own migrations table).
        migrate_platform(&pool).await?;
        sqlx::query("CREATE TABLE handled (event_id uuid NOT NULL)")
            .execute(&pool)
            .await?;
        Ok(pool)
    }

    async fn new_topic(&self) -> anyhow::Result<String> {
        let topic = format!("it-{}", Uuid::new_v4().simple());
        let admin: AdminClient<DefaultClientContext> = ClientConfig::new()
            .set("bootstrap.servers", &self.kafka)
            .create()?;
        let results = admin
            .create_topics(
                &[NewTopic::new(&topic, 1, TopicReplication::Fixed(1))],
                &AdminOptions::new(),
            )
            .await?;
        for r in results {
            r.map_err(|(t, e)| anyhow::anyhow!("create topic {t}: {e}"))?;
        }
        Ok(topic)
    }

    fn config(&self, topic: &str, consumer_name: &'static str) -> ConsumerConfig {
        ConsumerConfig::new(
            self.kafka.clone(),
            format!("g-{}", Uuid::new_v4().simple()),
            topic.to_owned(),
            consumer_name,
        )
    }

    async fn produce(
        &self,
        topic: &str,
        payload: &[u8],
        traceparent: Option<&str>,
    ) -> anyhow::Result<()> {
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &self.kafka)
            .set("message.timeout.ms", "10000")
            .create()?;
        let mut headers = OwnedHeaders::new().insert(Header {
            key: "event_type",
            value: Some(EVENT_TYPE),
        });
        if let Some(tp) = traceparent {
            headers = headers.insert(Header {
                key: "traceparent",
                value: Some(tp),
            });
        }
        producer
            .send(
                FutureRecord::to(topic)
                    .key("order-1")
                    .payload(payload)
                    .headers(headers),
                Duration::from_secs(10),
            )
            .await
            .map_err(|(e, _)| e)
            .context("produce")?;
        Ok(())
    }

    /// Committed offset of partition 0 for `group`, `None` when nothing committed yet.
    async fn committed(&self, group: &str, topic: &str) -> anyhow::Result<Option<i64>> {
        let kafka = self.kafka.clone();
        let group = group.to_owned();
        let topic = topic.to_owned();
        tokio::task::spawn_blocking(move || {
            let c: BaseConsumer = ClientConfig::new()
                .set("bootstrap.servers", &kafka)
                .set("group.id", &group)
                .create()?;
            let mut tpl = TopicPartitionList::new();
            tpl.add_partition(&topic, 0);
            let got = c.committed_offsets(tpl, Duration::from_secs(10))?;
            let offset = got.find_partition(&topic, 0).map(|p| p.offset());
            Ok(match offset {
                Some(Offset::Offset(n)) => Some(n),
                _ => None,
            })
        })
        .await?
    }
}

fn envelope(event_id: Uuid, event_type: &str, data: &serde_json::Value) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "event_id": event_id,
        "event_type": event_type,
        "occurred_at": "2026-11-24T10:15:30.123456Z",
        "data": data,
    }))
    .expect("serializable")
}

async fn handled_ids(pool: &PgPool) -> anyhow::Result<Vec<Uuid>> {
    Ok(
        sqlx::query_scalar("SELECT event_id FROM handled ORDER BY event_id")
            .fetch_all(pool)
            .await?,
    )
}

async fn processed_count(pool: &PgPool, consumer: &str) -> anyhow::Result<i64> {
    Ok(
        sqlx::query_scalar("SELECT count(*) FROM platform.processed_event WHERE consumer = $1")
            .bind(consumer)
            .fetch_one(pool)
            .await?,
    )
}

async fn wait_for<F, Fut>(what: &str, mut check: F) -> anyhow::Result<()>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = anyhow::Result<bool>>,
{
    let deadline = Instant::now() + WAIT;
    loop {
        match check().await {
            Ok(true) => return Ok(()),
            Ok(false) | Err(_) if Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
            Ok(false) => anyhow::bail!("timed out waiting for {what}"),
            Err(e) => return Err(e.context(format!("waiting for {what}"))),
        }
    }
}

/// Records every handled event in `handled`; `data.poison == true` is a permanently bad event.
struct RecordingHandler;

#[async_trait]
impl EventHandler for RecordingHandler {
    fn event_type(&self) -> &'static str {
        EVENT_TYPE
    }

    async fn handle(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        event: &Envelope,
    ) -> anyhow::Result<()> {
        if event.data.get("poison") == Some(&serde_json::Value::Bool(true)) {
            return Err(MalformedEvent::new("data.poison is set").into());
        }
        sqlx::query("INSERT INTO handled (event_id) VALUES ($1)")
            .bind(event.event_id)
            .execute(&mut **tx)
            .await?;
        Ok(())
    }
}

/// Captures the `error_type` of every WARN event emitted by the consumer under test.
#[derive(Clone, Default)]
struct WarnLog(Arc<Mutex<Vec<String>>>);

impl WarnLog {
    fn count(&self, error_type: &str) -> usize {
        self.0
            .lock()
            .expect("lock")
            .iter()
            .filter(|t| t.as_str() == error_type)
            .count()
    }
}

impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for WarnLog {
    fn on_event(&self, event: &tracing::Event<'_>, _: tracing_subscriber::layer::Context<'_, S>) {
        struct ErrorType(Option<String>);
        impl tracing::field::Visit for ErrorType {
            fn record_str(&mut self, f: &tracing::field::Field, v: &str) {
                if f.name() == "error_type" {
                    self.0 = Some(v.to_owned());
                }
            }
            fn record_debug(&mut self, _: &tracing::field::Field, _: &dyn std::fmt::Debug) {}
        }
        if *event.metadata().level() == tracing::Level::WARN {
            let mut v = ErrorType(None);
            event.record(&mut v);
            self.0.lock().expect("lock").push(v.0.unwrap_or_default());
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Metrics capture: one global in-memory meter provider, installed once before any consumer runs.
// Scenarios filter data points by their own (unique) topic.
// ---------------------------------------------------------------------------------------------

struct MetricsCapture {
    provider: SdkMeterProvider,
    exporter: InMemoryMetricExporter,
}

fn metrics_capture() -> &'static MetricsCapture {
    static CAPTURE: OnceLock<MetricsCapture> = OnceLock::new();
    CAPTURE.get_or_init(|| {
        let exporter = InMemoryMetricExporter::default();
        let provider = SdkMeterProvider::builder()
            .with_reader(PeriodicReader::builder(exporter.clone()).build())
            .build();
        opentelemetry::global::set_meter_provider(provider.clone());
        MetricsCapture { provider, exporter }
    })
}

/// `messaging.process.duration` counts per `outcome` for `topic` (cumulative).
fn outcome_counts(topic: &str) -> anyhow::Result<HashMap<String, u64>> {
    let capture = metrics_capture();
    capture.provider.force_flush()?;
    let mut counts: HashMap<String, u64> = HashMap::new();
    let mut latest: HashMap<String, u64> = HashMap::new();
    for rm in capture.exporter.get_finished_metrics()? {
        // Cumulative: a later export supersedes an earlier one, so keep the last seen per outcome.
        latest.clear();
        for sm in rm.scope_metrics() {
            for m in sm
                .metrics()
                .filter(|m| m.name() == "messaging.process.duration")
            {
                if let AggregatedMetrics::F64(MetricData::Histogram(h)) = m.data() {
                    for dp in h.data_points() {
                        let attr = |k: &str| {
                            dp.attributes()
                                .find(|kv| kv.key.as_str() == k)
                                .map(|kv| kv.value.to_string())
                        };
                        if attr("messaging.destination.name").as_deref() == Some(topic) {
                            ensure!(
                                attr("messaging.consumer.group.name").is_some(),
                                "group label present"
                            );
                            if let Some(o) = attr("outcome") {
                                *latest.entry(o).or_default() += dp.count();
                            }
                        }
                    }
                }
            }
        }
        if !latest.is_empty() {
            counts.clone_from(&latest);
        }
    }
    Ok(counts)
}

struct Running {
    shutdown: CancellationToken,
    task: JoinHandle<anyhow::Result<()>>,
}

impl Running {
    fn spawn<F>(fut: impl FnOnce(CancellationToken) -> F) -> Self
    where
        F: Future<Output = anyhow::Result<()>> + Send + 'static,
    {
        let shutdown = CancellationToken::new();
        let task = tokio::spawn(fut(shutdown.clone()));
        Self { shutdown, task }
    }

    fn start(cfg: ConsumerConfig, pool: PgPool) -> Self {
        Self::spawn(|t| run_consumer(cfg, pool, Arc::new(RecordingHandler), t))
    }

    fn start_logged(cfg: ConsumerConfig, pool: PgPool, log: &WarnLog) -> Self {
        let subscriber = tracing_subscriber::registry().with(log.clone());
        Self::spawn(|t| {
            run_consumer(cfg, pool, Arc::new(RecordingHandler), t).with_subscriber(subscriber)
        })
    }

    async fn stop(self) -> anyhow::Result<()> {
        ensure!(!self.task.is_finished(), "consumer exited before shutdown");
        self.shutdown.cancel();
        tokio::time::timeout(Duration::from_secs(15), self.task)
            .await
            .context("consumer did not stop within 15s of cancellation")??
    }
}

// ---------------------------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------------------------

async fn handles_event_once_and_commits(env: &Env) -> anyhow::Result<()> {
    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-once");
    let group = cfg.group_id.clone();
    let consumer = Running::start(cfg, pool.clone());

    let id = Uuid::new_v4();
    env.produce(
        &topic,
        &envelope(id, EVENT_TYPE, &serde_json::json!({"order_id": "o-1"})),
        None,
    )
    .await?;

    wait_for("offset 1 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(1))
    })
    .await?;
    ensure!(
        handled_ids(&pool).await? == vec![id],
        "event handled exactly once"
    );
    ensure!(
        processed_count(&pool, "it-once").await? == 1,
        "processed_event row recorded"
    );
    consumer.stop().await
}

async fn duplicate_event_id_processed_once(env: &Env) -> anyhow::Result<()> {
    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-dup");
    let group = cfg.group_id.clone();
    let consumer = Running::start(cfg, pool.clone());

    let id = Uuid::new_v4();
    let payload = envelope(id, EVENT_TYPE, &serde_json::json!({"order_id": "o-1"}));
    env.produce(&topic, &payload, None).await?;
    env.produce(&topic, &payload, None).await?;

    wait_for("offset 2 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(2))
    })
    .await?;
    ensure!(
        handled_ids(&pool).await? == vec![id],
        "duplicate handled exactly once"
    );
    ensure!(
        processed_count(&pool, "it-dup").await? == 1,
        "one processed_event row"
    );
    consumer.stop().await
}

async fn malformed_messages_are_skipped_and_consumer_continues(env: &Env) -> anyhow::Result<()> {
    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-malformed");
    let group = cfg.group_id.clone();
    let log = WarnLog::default();
    let consumer = Running::start_logged(cfg, pool.clone(), &log);

    // 1. not JSON
    env.produce(&topic, b"this is not json", None).await?;
    // 2. missing event_id
    env.produce(
        &topic,
        br#"{"event_type":"OrderPlaced","occurred_at":"2026-11-24T10:15:30Z","data":{}}"#,
        None,
    )
    .await?;
    // 3. other event type (not for this handler)
    env.produce(
        &topic,
        &envelope(Uuid::new_v4(), "OrderCancelled", &serde_json::json!({})),
        None,
    )
    .await?;
    // 4. handler rejects it permanently (e.g. `data` missing a required field)
    env.produce(
        &topic,
        &envelope(
            Uuid::new_v4(),
            EVENT_TYPE,
            &serde_json::json!({"poison": true}),
        ),
        None,
    )
    .await?;
    // 5. valid
    let valid = Uuid::new_v4();
    env.produce(
        &topic,
        &envelope(valid, EVENT_TYPE, &serde_json::json!({})),
        None,
    )
    .await?;

    wait_for("offset 5 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(5))
    })
    .await?;
    ensure!(
        handled_ids(&pool).await? == vec![valid],
        "only the valid event handled"
    );
    ensure!(
        processed_count(&pool, "it-malformed").await? == 1,
        "rejected event left no processed_event row"
    );
    ensure!(
        log.count("MalformedEvent") == 3,
        "3 malformed messages logged at WARN (not JSON, missing event_id, handler-rejected)"
    );
    consumer.stop().await
}

async fn consumer_metrics_record_outcomes(env: &Env) -> anyhow::Result<()> {
    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-metrics");
    let group = cfg.group_id.clone();
    let consumer = Running::start(cfg, pool.clone());

    let id = Uuid::new_v4();
    let valid = envelope(id, EVENT_TYPE, &serde_json::json!({}));
    env.produce(&topic, &valid, None).await?;
    env.produce(&topic, &valid, None).await?; // same event_id: duplicate
    env.produce(&topic, b"this is not json", None).await?;
    env.produce(
        &topic,
        &envelope(Uuid::new_v4(), "OrderCancelled", &serde_json::json!({})),
        None,
    )
    .await?;

    wait_for("offset 4 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(4))
    })
    .await?;
    wait_for("all four outcomes recorded", || async {
        let c = outcome_counts(&topic)?;
        Ok(["processed", "duplicate", "malformed", "ignored"]
            .iter()
            .all(|o| c.get(*o) == Some(&1)))
    })
    .await?;
    ensure!(
        !outcome_counts(&topic)?.contains_key("failed"),
        "no failed attempts"
    );
    consumer.stop().await
}

async fn continues_trace_from_traceparent_header(env: &Env) -> anyhow::Result<()> {
    opentelemetry::global::set_text_map_propagator(TraceContextPropagator::new());
    let exporter = InMemorySpanExporter::default();
    let provider = SdkTracerProvider::builder()
        .with_simple_exporter(exporter.clone())
        .build();
    let subscriber = tracing_subscriber::registry()
        .with(tracing_opentelemetry::layer().with_tracer(provider.tracer("consumer-it")));

    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-trace");
    let group = cfg.group_id.clone();
    let consumer = Running::spawn(|t| {
        run_consumer(cfg, pool.clone(), Arc::new(RecordingHandler), t).with_subscriber(subscriber)
    });

    let traceparent = format!("00-{TRACE_ID}-{PARENT_SPAN_ID}-01");
    env.produce(
        &topic,
        &envelope(Uuid::new_v4(), EVENT_TYPE, &serde_json::json!({})),
        Some(&traceparent),
    )
    .await?;

    wait_for("offset 1 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(1))
    })
    .await?;
    let name = format!("{topic} process");
    wait_for("consumer span exported", || async {
        Ok(exporter
            .get_finished_spans()?
            .iter()
            .any(|s| s.name == name))
    })
    .await?;
    let spans = exporter.get_finished_spans()?;
    let span = spans
        .iter()
        .find(|s| s.name == name)
        .context("consumer span")?;
    ensure!(
        span.span_context.trace_id().to_string() == TRACE_ID,
        "trace id continued, got {}",
        span.span_context.trace_id()
    );
    ensure!(
        span.parent_span_id.to_string() == PARENT_SPAN_ID,
        "remote parent span id, got {}",
        span.parent_span_id
    );
    ensure!(span.span_kind == SpanKind::Consumer, "span kind consumer");
    let attr = |key: &str| {
        span.attributes
            .iter()
            .find(|kv| kv.key.as_str() == key)
            .map(|kv| kv.value.to_string())
    };
    ensure!(
        attr("messaging.system").as_deref() == Some("kafka"),
        "messaging.system"
    );
    ensure!(
        attr("messaging.destination.name").as_deref() == Some(topic.as_str()),
        "messaging.destination.name"
    );
    ensure!(
        attr("messaging.kafka.message.offset").as_deref() == Some("0"),
        "messaging.kafka.message.offset"
    );
    ensure!(
        attr("messaging.destination.partition.id").as_deref() == Some("0"),
        "messaging.destination.partition.id"
    );
    consumer.stop().await
}

async fn db_down_retries_until_db_back(env: &Env) -> anyhow::Result<()> {
    // Short acquire timeout so a paused DB surfaces as errors (exercising the retry path) instead
    // of a single hanging acquire.
    let pool = env
        .new_db(PgPoolOptions::new().acquire_timeout(Duration::from_secs(2)))
        .await?;
    let topic = env.new_topic().await?;
    let mut cfg = env.config(&topic, "it-dbdown");
    cfg.max_backoff = Duration::from_secs(2);
    let group = cfg.group_id.clone();
    let log = WarnLog::default();
    let consumer = Running::start_logged(cfg, pool.clone(), &log);

    // Warm up: group joined and partition assigned.
    let first = Uuid::new_v4();
    env.produce(
        &topic,
        &envelope(first, EVENT_TYPE, &serde_json::json!({})),
        None,
    )
    .await?;
    wait_for("offset 1 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(1))
    })
    .await?;

    env.pg.pause().await?;
    let second = Uuid::new_v4();
    let outcome = async {
        env.produce(
            &topic,
            &envelope(second, EVENT_TYPE, &serde_json::json!({})),
            None,
        )
        .await?;
        tokio::time::sleep(Duration::from_secs(3)).await;
        ensure!(!consumer.task.is_finished(), "consumer survived the outage");
        ensure!(
            env.committed(&group, &topic).await? == Some(1),
            "offset not committed while DB is down"
        );
        Ok(())
    }
    .await;
    env.pg.unpause().await?;
    outcome?;

    wait_for("offset 2 committed after DB is back", || async {
        Ok(env.committed(&group, &topic).await? == Some(2))
    })
    .await?;
    let mut expected = vec![first, second];
    expected.sort();
    ensure!(
        handled_ids(&pool).await? == expected,
        "second event processed exactly once"
    );
    ensure!(
        log.count("EventProcessingFailed") >= 1,
        "DB outage surfaced as a logged, retried processing failure"
    );
    wait_for("processed and failed outcomes recorded", || async {
        let c = outcome_counts(&topic)?;
        // Two distinct events (warm-up and the one delayed by the outage), each processed once.
        Ok(c.get("failed").copied().unwrap_or(0) >= 1 && c.get("processed") == Some(&2))
    })
    .await?;
    consumer.stop().await
}

// ---------------------------------------------------------------------------------------------

/// Two replicas starting at once on a fresh DB: both `migrate_platform` calls must succeed.
async fn concurrent_platform_migrations_on_fresh_db(env: &Env) -> anyhow::Result<()> {
    let url = env.empty_db_url().await?;
    let (a, b) = tokio::try_join!(PgPool::connect(&url), PgPool::connect(&url))?;
    let (ra, rb) = tokio::join!(migrate_platform(&a), migrate_platform(&b));
    ra.context("replica A migrate")?;
    rb.context("replica B migrate")?;
    let applied: i64 = sqlx::query_scalar("SELECT count(*) FROM platform._sqlx_migrations")
        .fetch_one(&a)
        .await?;
    // Same embedded set `migrate_platform` uses; each must be recorded exactly once.
    let embedded = i64::try_from(sqlx::migrate!("./migrations").iter().count())?;
    ensure!(
        applied == embedded,
        "each platform migration applied once: expected {embedded}, got {applied}"
    );
    sqlx::query("SELECT 1 FROM platform.processed_event LIMIT 1")
        .execute(&b)
        .await?;
    Ok(())
}

/// Shutdown while an attempt hangs on a paused DB: `run_consumer` returns promptly, the in-flight
/// transaction is dropped (rolled back) and the offset is not advanced past that message.
async fn shutdown_during_inflight_attempt_is_prompt(env: &Env) -> anyhow::Result<()> {
    // Default 30s acquire timeout: without per-attempt cancellation, shutdown would wait for it.
    let pool = env.new_db(PgPoolOptions::new()).await?;
    let topic = env.new_topic().await?;
    let cfg = env.config(&topic, "it-shutdown");
    let group = cfg.group_id.clone();
    let Running { shutdown, task } = Running::start(cfg, pool.clone());

    let first = Uuid::new_v4();
    env.produce(
        &topic,
        &envelope(first, EVENT_TYPE, &serde_json::json!({})),
        None,
    )
    .await?;
    wait_for("offset 1 committed", || async {
        Ok(env.committed(&group, &topic).await? == Some(1))
    })
    .await?;

    env.pg.pause().await?;
    let outcome = async {
        env.produce(
            &topic,
            &envelope(Uuid::new_v4(), EVENT_TYPE, &serde_json::json!({})),
            None,
        )
        .await?;
        // Let the consumer receive it and block inside the attempt.
        tokio::time::sleep(Duration::from_millis(1500)).await;
        ensure!(!task.is_finished(), "consumer still running");
        shutdown.cancel();
        let started = Instant::now();
        tokio::time::timeout(Duration::from_secs(2), task)
            .await
            .context("run_consumer did not return within 2s of cancellation")???;
        eprintln!(
            "  run_consumer returned {}ms after cancel",
            started.elapsed().as_millis()
        );
        Ok(())
    }
    .await;
    env.pg.unpause().await?;
    outcome?;

    ensure!(
        env.committed(&group, &topic).await? == Some(1),
        "offset not advanced past the in-flight message"
    );
    ensure!(
        handled_ids(&pool).await? == vec![first],
        "in-flight event not applied"
    );
    ensure!(
        processed_count(&pool, "it-shutdown").await? == 1,
        "in-flight idempotency marker rolled back"
    );
    pool.close().await;
    Ok(())
}

macro_rules! scenario {
    ($env:expr, $failures:expr, $name:ident) => {{
        let t = Instant::now();
        let res = $name($env).await;
        eprintln!(
            "scenario {} ... {} ({:.1}s)",
            stringify!($name),
            if res.is_ok() { "ok" } else { "FAILED" },
            t.elapsed().as_secs_f64()
        );
        if let Err(e) = res {
            $failures.push(format!("{}: {e:#}", stringify!($name)));
        }
    }};
}

#[test]
fn consumer_runner_integration() {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("runtime");
    rt.block_on(async {
        let started = Instant::now();
        let env = Env::start().await.expect("containers start");
        eprintln!(
            "containers ready in {:.1}s",
            started.elapsed().as_secs_f64()
        );
        metrics_capture();
        let mut failures: Vec<String> = Vec::new();
        scenario!(&env, failures, concurrent_platform_migrations_on_fresh_db);
        scenario!(&env, failures, handles_event_once_and_commits);
        scenario!(&env, failures, duplicate_event_id_processed_once);
        scenario!(
            &env,
            failures,
            malformed_messages_are_skipped_and_consumer_continues
        );
        scenario!(&env, failures, consumer_metrics_record_outcomes);
        scenario!(&env, failures, continues_trace_from_traceparent_header);
        // Last: these pause the shared Postgres.
        scenario!(&env, failures, db_down_retries_until_db_back);
        scenario!(&env, failures, shutdown_during_inflight_attempt_is_prompt);
        eprintln!("consumer IT total {:.1}s", started.elapsed().as_secs_f64());
        assert!(
            failures.is_empty(),
            "failed scenarios:\n{}",
            failures.join("\n")
        );
    });
}
