//! End-to-end tests of the wired service (Postgres 17 + Redpanda via testcontainers). The app runs
//! in-process on a random port.
//!
//! All scenarios share one Postgres and one Redpanda container (weak dev machine) and run
//! sequentially inside a single test, so the containers are started once and removed at the end.
//! Each scenario gets its own database and consumer group; the topic is the real
//! `orders.order-placed.v1`, so a fresh group also sees earlier scenarios' events, which is
//! harmless because assertions are per order id.

use std::borrow::Cow;
use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{ensure, Context as _};
use notify_svc::adapters::db::{self, NotificationRepositoryAdapter};
use notify_svc::adapters::messaging::TOPIC;
use notify_svc::adapters::system::{RandomIds, SystemClock};
use notify_svc::application::NotificationService;
use notify_svc::config::Config;
use notify_svc::domain::{Channel, OrderPlaced};
use rdkafka::admin::{AdminClient, AdminOptions, NewTopic, TopicReplication};
use rdkafka::client::DefaultClientContext;
use rdkafka::message::{Header, OwnedHeaders};
use rdkafka::producer::{FutureProducer, FutureRecord};
use rdkafka::ClientConfig;
use sqlx::PgPool;
use testcontainers::core::{ContainerPort, ContainerState, ExecCommand, WaitFor};
use testcontainers::runners::AsyncRunner;
use testcontainers::{ContainerAsync, Image, ImageExt, TestcontainersError};
use testcontainers_modules::postgres::Postgres as PostgresImage;
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

const TRACEPARENT: &str = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

// ---------------------------------------------------------------------------------------------
// Redpanda image (same as platform-core's consumer IT): the advertised address must contain the
// mapped host port, known only after start, so the container waits for a start script.
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
    _pg: ContainerAsync<PostgresImage>,
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
        let admin: AdminClient<DefaultClientContext> = ClientConfig::new()
            .set("bootstrap.servers", &kafka)
            .create()?;
        for r in admin
            .create_topics(
                &[NewTopic::new(TOPIC, 1, TopicReplication::Fixed(1))],
                &AdminOptions::new(),
            )
            .await?
        {
            r.map_err(|(t, e)| anyhow::anyhow!("create topic {t}: {e}"))?;
        }
        Ok(Self {
            _pg: pg,
            pg_base,
            kafka,
            _redpanda: redpanda,
        })
    }

    /// URL of a freshly created, empty database.
    async fn new_db_url(&self) -> anyhow::Result<String> {
        let name = format!("t_{}", Uuid::new_v4().simple());
        let admin = PgPool::connect(&format!("{}/postgres", self.pg_base)).await?;
        sqlx::query(sqlx::AssertSqlSafe(format!("CREATE DATABASE {name}")))
            .execute(&admin)
            .await?;
        admin.close().await;
        Ok(format!("{}/{name}", self.pg_base))
    }

    async fn produce(&self, payload: &[u8], traceparent: Option<&str>) -> anyhow::Result<()> {
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &self.kafka)
            .set("message.timeout.ms", "10000")
            .create()?;
        let mut headers = OwnedHeaders::new().insert(Header {
            key: "event_type",
            value: Some("OrderPlaced"),
        });
        if let Some(tp) = traceparent {
            headers = headers.insert(Header {
                key: "traceparent",
                value: Some(tp),
            });
        }
        producer
            .send(
                FutureRecord::to(TOPIC)
                    .key("order")
                    .payload(payload)
                    .headers(headers),
                Duration::from_secs(10),
            )
            .await
            .map_err(|(e, _)| e)
            .context("produce")?;
        Ok(())
    }
}

fn config(db_url: String, kafka: &str) -> Config {
    Config {
        db_url,
        kafka_bootstrap_servers: kafka.to_owned(),
        port: 0,
        kafka_group_id: format!("g-{}", Uuid::new_v4().simple()),
        service_name: "notify-svc".to_owned(),
    }
}

/// Envelope as order-svc's outbox relay publishes it.
fn envelope(event_id: Uuid, order_id: Uuid, total: &str) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "event_id": event_id,
        "event_type": "OrderPlaced",
        "occurred_at": "2026-11-24T10:15:30.123456Z",
        "data": {
            "order_id": order_id,
            "customer_id": "cust-1",
            "total": total,
            "item_count": 2
        },
    }))
    .expect("serializable")
}

struct RunningApp {
    addr: std::net::SocketAddr,
    shutdown: CancellationToken,
    task: JoinHandle<anyhow::Result<()>>,
}

impl RunningApp {
    async fn start(cfg: Config) -> anyhow::Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let addr = listener.local_addr()?;
        let shutdown = CancellationToken::new();
        let task = tokio::spawn(notify_svc::app::serve(cfg, listener, shutdown.clone()));
        Ok(Self {
            addr,
            shutdown,
            task,
        })
    }

    async fn get(&self, path: &str) -> anyhow::Result<(u16, serde_json::Value)> {
        http_get(self.addr, path).await
    }

    /// Cancels and expects a clean, prompt exit.
    async fn stop(self) -> anyhow::Result<()> {
        self.shutdown.cancel();
        tokio::time::timeout(Duration::from_secs(10), self.task)
            .await
            .context("app did not stop within 10s")??
            .context("app returned an error")
    }
}

/// Minimal HTTP/1.1 GET (no client crate needed); returns status and JSON body.
async fn http_get(
    addr: std::net::SocketAddr,
    path: &str,
) -> anyhow::Result<(u16, serde_json::Value)> {
    let mut stream = TcpStream::connect(addr).await?;
    let req = format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    stream.write_all(req.as_bytes()).await?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await?;
    let text = String::from_utf8(raw)?;
    let (head, body) = text.split_once("\r\n\r\n").context("no header end")?;
    let status: u16 = head
        .split_whitespace()
        .nth(1)
        .context("no status")?
        .parse()?;
    let body = if body.is_empty() {
        serde_json::Value::Null
    } else {
        serde_json::from_str(body).with_context(|| format!("body {body:?}"))?
    };
    Ok((status, body))
}

async fn wait_for<F, Fut>(what: &str, within: Duration, mut check: F) -> anyhow::Result<()>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = anyhow::Result<bool>>,
{
    let deadline = Instant::now() + within;
    loop {
        match check().await {
            Ok(true) => return Ok(()),
            Ok(false) | Err(_) if Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
            Ok(false) => anyhow::bail!("timed out after {within:?} waiting for {what}"),
            Err(e) => return Err(e.context(format!("waiting for {what}"))),
        }
    }
}

async fn listed_orders(app: &RunningApp) -> anyhow::Result<Vec<String>> {
    let (status, body) = app.get("/notifications?limit=200").await?;
    ensure!(status == 200, "GET /notifications -> {status}: {body}");
    Ok(body
        .as_array()
        .context("array")?
        .iter()
        .filter_map(|n| n["orderId"].as_str().map(str::to_owned))
        .collect())
}

/// Waits until the app's consumer has joined its group and processed something (a warm-up event),
/// so the 10s budget below measures processing, not the group join.
async fn warm_up(env: &Env, app: &RunningApp) -> anyhow::Result<()> {
    let order = Uuid::new_v4();
    env.produce(&envelope(Uuid::new_v4(), order, "1.00"), None)
        .await?;
    wait_for("warm-up event listed", Duration::from_secs(60), || async {
        Ok(listed_orders(app).await?.contains(&order.to_string()))
    })
    .await
}

async fn count_rows(db_url: &str, order: Uuid) -> anyhow::Result<i64> {
    let pool = PgPool::connect(db_url).await?;
    let n = sqlx::query_scalar("SELECT count(*) FROM notifications WHERE order_id = $1")
        .bind(order)
        .fetch_one(&pool)
        .await?;
    pool.close().await;
    Ok(n)
}

// ---------------------------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------------------------

/// `NotificationService::record` + the SQL adapter against the real schema.
async fn record_and_list_against_real_db(env: &Env) -> anyhow::Result<()> {
    let pool = db::connect(&env.new_db_url().await?).await?;
    db::migrate(&pool).await?;
    db::migrate(&pool).await?; // idempotent

    let timeout: String = sqlx::query_scalar("SHOW statement_timeout")
        .fetch_one(&pool)
        .await?;
    ensure!(timeout == "10s", "statement_timeout = {timeout}");

    let service = NotificationService::new(
        NotificationRepositoryAdapter::new(pool.clone()),
        Arc::new(SystemClock),
        Arc::new(RandomIds),
    );
    let mut recorded = Vec::new();
    for i in 0..3u32 {
        let e = OrderPlaced {
            order_id: Uuid::new_v4(),
            customer_id: format!("c-{i}"),
            total: "10.00".into(),
            item_count: i + 1,
        };
        let mut tx = pool.begin().await?;
        recorded.push(service.record(&mut tx, &e).await?);
        tx.commit().await?;
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let listed = service.list(Some(2)).await?;
    ensure!(listed.len() == 2, "limit honoured");
    ensure!(listed[0] == recorded[2], "newest first: {:?}", listed[0]);
    ensure!(listed[1] == recorded[1], "second newest");
    ensure!(listed[0].channel() == Channel::Log, "channel round-trips");

    // Rolled-back transaction leaves no row.
    let e = OrderPlaced {
        order_id: Uuid::new_v4(),
        customer_id: "c".into(),
        total: "1.00".into(),
        item_count: 1,
    };
    let mut tx = pool.begin().await?;
    service.record(&mut tx, &e).await?;
    tx.rollback().await?;
    ensure!(
        service.list(None).await?.len() == 3,
        "rollback discarded the row"
    );

    let ready = db::PgReadiness::new(pool.clone());
    ensure!(
        platform_core::http::ReadinessCheck::ready(&ready).await,
        "ready"
    );
    pool.close().await;
    ensure!(
        !platform_core::http::ReadinessCheck::ready(&ready).await,
        "closed pool is not ready"
    );
    Ok(())
}

async fn consumes_order_placed_end_to_end(env: &Env) -> anyhow::Result<()> {
    let app = RunningApp::start(config(env.new_db_url().await?, &env.kafka)).await?;
    let outcome = async {
        warm_up(env, &app).await?;
        let (status, body) = app.get("/health/ready").await?;
        ensure!(status == 200, "ready -> {status}: {body}");

        let order = Uuid::new_v4();
        env.produce(&envelope(Uuid::new_v4(), order, "25.50"), Some(TRACEPARENT))
            .await?;
        wait_for("order listed", Duration::from_secs(10), || async {
            Ok(listed_orders(&app).await?.contains(&order.to_string()))
        })
        .await?;
        let (_, body) = app.get("/notifications?limit=1").await?;
        ensure!(
            body[0]["orderId"] == order.to_string(),
            "newest first: {body}"
        );
        ensure!(
            body[0]["message"] == format!("Order {order} placed: 2 item(s), total 25.50"),
            "message: {body}"
        );
        Ok(())
    }
    .await;
    app.stop().await?;
    outcome
}

async fn duplicate_event_id_yields_one_row(env: &Env) -> anyhow::Result<()> {
    let db_url = env.new_db_url().await?;
    let app = RunningApp::start(config(db_url.clone(), &env.kafka)).await?;
    let outcome = async {
        warm_up(env, &app).await?;
        let (event_id, order) = (Uuid::new_v4(), Uuid::new_v4());
        let payload = envelope(event_id, order, "5.00");
        env.produce(&payload, None).await?;
        env.produce(&payload, None).await?;
        // A later marker event proves both copies were consumed (single partition, in order).
        let marker = Uuid::new_v4();
        env.produce(&envelope(Uuid::new_v4(), marker, "1.00"), None)
            .await?;
        wait_for("marker listed", Duration::from_secs(10), || async {
            Ok(listed_orders(&app).await?.contains(&marker.to_string()))
        })
        .await?;
        let rows = count_rows(&db_url, order).await?;
        ensure!(rows == 1, "duplicate produced {rows} rows");
        Ok(())
    }
    .await;
    app.stop().await?;
    outcome
}

async fn invalid_data_is_skipped_and_consumer_continues(env: &Env) -> anyhow::Result<()> {
    let db_url = env.new_db_url().await?;
    let app = RunningApp::start(config(db_url.clone(), &env.kafka)).await?;
    let outcome = async {
        warm_up(env, &app).await?;
        let bad_total = Uuid::new_v4();
        env.produce(&envelope(Uuid::new_v4(), bad_total, "abc"), None)
            .await?;
        let missing_field = serde_json::to_vec(&serde_json::json!({
            "event_id": Uuid::new_v4(),
            "event_type": "OrderPlaced",
            "occurred_at": "2026-11-24T10:15:30Z",
            "data": {"order_id": Uuid::new_v4()}
        }))?;
        env.produce(&missing_field, None).await?;
        let good = Uuid::new_v4();
        env.produce(&envelope(Uuid::new_v4(), good, "3.00"), None)
            .await?;
        wait_for(
            "valid event after invalid ones",
            Duration::from_secs(10),
            || async { Ok(listed_orders(&app).await?.contains(&good.to_string())) },
        )
        .await?;
        ensure!(
            count_rows(&db_url, bad_total).await? == 0,
            "invalid total stored"
        );
        Ok(())
    }
    .await;
    app.stop().await?;
    outcome
}

async fn kafka_unavailable_http_still_serves(env: &Env) -> anyhow::Result<()> {
    let started = Instant::now();
    let app = RunningApp::start(config(env.new_db_url().await?, "127.0.0.1:1")).await?;
    let outcome = async {
        wait_for("/health/live 200", Duration::from_secs(5), || async {
            Ok(app.get("/health/live").await?.0 == 200)
        })
        .await?;
        eprintln!(
            "  /health/live 200 after {}ms with Kafka down",
            started.elapsed().as_millis()
        );
        let (status, body) = app.get("/notifications").await?;
        ensure!(
            status == 200 && body == serde_json::json!([]),
            "{status}: {body}"
        );
        let (status, _) = app.get("/health/ready").await?;
        ensure!(status == 200, "ready reflects DB only");
        ensure!(!app.task.is_finished(), "app still running");
        Ok(())
    }
    .await;
    app.stop().await?;
    outcome
}

async fn missing_database_fails_fast(env: &Env) -> anyhow::Result<()> {
    let url = format!("{}/does_not_exist", env.pg_base);
    let app = RunningApp::start(config(url, &env.kafka)).await?;
    let res = tokio::time::timeout(Duration::from_secs(15), app.task)
        .await
        .context("app did not fail within 15s")??;
    ensure!(res.is_err(), "startup must fail without a database");
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
fn notify_svc_flow_integration() {
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
        let mut failures: Vec<String> = Vec::new();
        scenario!(&env, failures, record_and_list_against_real_db);
        scenario!(&env, failures, kafka_unavailable_http_still_serves);
        scenario!(&env, failures, missing_database_fails_fast);
        scenario!(&env, failures, consumes_order_placed_end_to_end);
        scenario!(&env, failures, duplicate_event_id_yields_one_row);
        scenario!(
            &env,
            failures,
            invalid_data_is_skipped_and_consumer_continues
        );
        eprintln!("flow IT total {:.1}s", started.elapsed().as_secs_f64());
        assert!(
            failures.is_empty(),
            "failed scenarios:\n{}",
            failures.join("\n")
        );
    });
}
