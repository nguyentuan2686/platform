//! Idempotent Kafka consumer runner with W3C trace continuation.
//!
//! Offsets are committed manually (`enable.auto.commit=false`) and only after the message is
//! finished: processed (DB transaction committed), duplicate, or skipped as malformed / other
//! event type. A transient failure (DB down, handler error) is retried in place with exponential
//! backoff and the offset is not committed, so the message is never lost; the partition simply
//! does not advance until it succeeds.
//!
//! Messages are processed one at a time, so a message stuck in retry blocks every partition this
//! consumer owns (head-of-line blocking across partitions), not only its own.
//!
//! Commits use `CommitMode::Async`: the sync variant blocks the calling thread on a broker
//! round-trip, which would stall a tokio worker. An async commit is enqueued in order by
//! librdkafka; if it is lost (crash, rebalance) the message is redelivered and the idempotency
//! marker in `platform.processed_event` turns the redelivery into a no-op. On shutdown the offsets
//! of all finished messages are committed once more with `CommitMode::Sync`, flushing pending
//! async commits before the client is dropped.
//!
//! Shutdown is prompt: cancellation is observed while waiting for messages, during backoff and
//! during a processing attempt. A cancelled attempt is dropped, which drops its transaction (rolled
//! back, never committed) and leaves its offset uncommitted, so the message is redelivered.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::Context as _;
use opentelemetry::propagation::Extractor;
use rdkafka::consumer::{CommitMode, Consumer as _, StreamConsumer};
use rdkafka::message::{Headers as _, OwnedHeaders, OwnedMessage};
use rdkafka::{ClientConfig, Message as _, Offset, TopicPartitionList};
use sqlx::PgPool;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn, Instrument as _, Span};
use tracing_opentelemetry::OpenTelemetrySpanExt as _;

use super::idempotency::{process_once, Outcome};
use super::{Envelope, EventHandler, MalformedEvent};

/// Default upper bound for the retry backoff.
pub const DEFAULT_MAX_BACKOFF: Duration = Duration::from_secs(30);
const INITIAL_BACKOFF: Duration = Duration::from_secs(1);

#[derive(Debug, Clone)]
pub struct ConsumerConfig {
    pub bootstrap_servers: String,
    pub group_id: String,
    pub topic: String,
    /// Stable name of this consumer; part of the idempotency key `(consumer, event_id)`.
    pub consumer_name: &'static str,
    /// Retry backoff starts at 1s and doubles up to this value.
    pub max_backoff: Duration,
}

impl ConsumerConfig {
    /// Config with `max_backoff` = [`DEFAULT_MAX_BACKOFF`].
    pub fn new(
        bootstrap_servers: impl Into<String>,
        group_id: impl Into<String>,
        topic: impl Into<String>,
        consumer_name: &'static str,
    ) -> Self {
        Self {
            bootstrap_servers: bootstrap_servers.into(),
            group_id: group_id.into(),
            topic: topic.into(),
            consumer_name,
            max_backoff: DEFAULT_MAX_BACKOFF,
        }
    }
}

/// Consumes `cfg.topic` until `shutdown` is cancelled, dispatching `handler.event_type()` events
/// to `handler` exactly once per `(consumer_name, event_id)`.
///
/// Returns `Ok(())` after cancellation; errors only if the Kafka client cannot be created or the
/// subscription is rejected. Broker unavailability is logged and retried, never returned.
/// Cancellation is observed while waiting for messages, during retry backoff and during a
/// processing attempt (the attempt's transaction is dropped and rolled back; its message is
/// redelivered after restart). Before returning, finished offsets are committed synchronously.
pub async fn run_consumer(
    cfg: ConsumerConfig,
    pool: PgPool,
    handler: Arc<dyn EventHandler>,
    shutdown: CancellationToken,
) -> anyhow::Result<()> {
    let consumer: StreamConsumer = ClientConfig::new()
        .set("bootstrap.servers", &cfg.bootstrap_servers)
        .set("group.id", &cfg.group_id)
        .set("enable.auto.commit", "false")
        .set("auto.offset.reset", "earliest")
        .set("enable.partition.eof", "false")
        .create()
        .context("creating Kafka consumer")?;
    consumer
        .subscribe(&[cfg.topic.as_str()])
        .with_context(|| format!("subscribing to {}", cfg.topic))?;
    info!(
        topic = %cfg.topic,
        group_id = %cfg.group_id,
        consumer = cfg.consumer_name,
        "kafka consumer started"
    );

    let runner = Runner {
        consumer: &consumer,
        cfg: &cfg,
        pool: &pool,
        handler: handler.as_ref(),
        shutdown: &shutdown,
        finished: Mutex::default(),
    };
    let mut recv_backoff = INITIAL_BACKOFF.min(cfg.max_backoff);
    loop {
        // The borrowed message is detached immediately: it is not `Send` and must not live across
        // an await point of this (spawned) future.
        let received = tokio::select! {
            biased;
            () = shutdown.cancelled() => break,
            m = consumer.recv() => m.map(|m| m.detach()),
        };
        match received {
            Ok(msg) => {
                recv_backoff = INITIAL_BACKOFF.min(cfg.max_backoff);
                let span = consumer_span(&msg);
                if runner.process(&msg).instrument(span).await == Flow::Stop {
                    break;
                }
            }
            Err(e) => {
                warn!(
                    error = %e,
                    error_type = "KafkaReceiveFailed",
                    topic = %cfg.topic,
                    backoff_ms = duration_ms(recv_backoff),
                    "kafka receive failed, retrying"
                );
                if !sleep_or_cancelled(recv_backoff, &shutdown).await {
                    break;
                }
                recv_backoff = (recv_backoff * 2).min(cfg.max_backoff);
            }
        }
    }
    runner.flush_commits();
    info!(topic = %cfg.topic, consumer = cfg.consumer_name, "kafka consumer stopped");
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Flow {
    Continue,
    Stop,
}

struct Runner<'a> {
    consumer: &'a StreamConsumer,
    cfg: &'a ConsumerConfig,
    pool: &'a PgPool,
    handler: &'a dyn EventHandler,
    shutdown: &'a CancellationToken,
    /// Next offset to commit per (topic, partition), for finished messages only.
    finished: Mutex<HashMap<(String, i32), i64>>,
}

impl Runner<'_> {
    async fn process(&self, msg: &OwnedMessage) -> Flow {
        let envelope = match msg.payload().map(Envelope::parse) {
            Some(Ok(e)) => e,
            Some(Err(e)) => {
                self.skip_malformed(msg, &e.to_string());
                return Flow::Continue;
            }
            None => {
                self.skip_malformed(msg, "empty payload");
                return Flow::Continue;
            }
        };
        if envelope.event_type != self.handler.event_type() {
            debug!(
                topic = msg.topic(),
                partition = msg.partition(),
                offset = msg.offset(),
                event_id = %envelope.event_id,
                event_type = %envelope.event_type,
                "skipping event of another type"
            );
            self.commit(msg);
            return Flow::Continue;
        }

        let mut backoff = INITIAL_BACKOFF.min(self.cfg.max_backoff);
        loop {
            // Dropping the attempt on shutdown drops its Transaction (rollback); no offset commit.
            let result = tokio::select! {
                biased;
                () = self.shutdown.cancelled() => {
                    info!(
                        topic = msg.topic(),
                        partition = msg.partition(),
                        offset = msg.offset(),
                        event_id = %envelope.event_id,
                        "shutdown during processing; attempt abandoned, offset not committed"
                    );
                    return Flow::Stop;
                }
                r = process_once(self.pool, self.cfg.consumer_name, self.handler, &envelope) => r,
            };
            match result {
                Ok(outcome) => {
                    let what = match outcome {
                        Outcome::Processed => "event processed",
                        Outcome::Duplicate => "duplicate event skipped",
                    };
                    info!(
                        topic = msg.topic(),
                        partition = msg.partition(),
                        offset = msg.offset(),
                        event_id = %envelope.event_id,
                        event_type = %envelope.event_type,
                        "{what}"
                    );
                    self.commit(msg);
                    return Flow::Continue;
                }
                Err(e) => {
                    if let Some(m) = e.chain().find_map(|c| c.downcast_ref::<MalformedEvent>()) {
                        self.skip_malformed(msg, m.reason());
                        return Flow::Continue;
                    }
                    warn!(
                        error = %format!("{e:#}"),
                        error_type = "EventProcessingFailed",
                        topic = msg.topic(),
                        partition = msg.partition(),
                        offset = msg.offset(),
                        event_id = %envelope.event_id,
                        backoff_ms = duration_ms(backoff),
                        "event processing failed, retrying (offset not committed)"
                    );
                    if !sleep_or_cancelled(backoff, self.shutdown).await {
                        return Flow::Stop;
                    }
                    backoff = (backoff * 2).min(self.cfg.max_backoff);
                }
            }
        }
    }

    /// WARN (never the payload) and commit past the message: it can never succeed.
    fn skip_malformed(&self, msg: &OwnedMessage, reason: &str) {
        warn!(
            error = %reason,
            error_type = "MalformedEvent",
            topic = msg.topic(),
            partition = msg.partition(),
            offset = msg.offset(),
            "skipping malformed message"
        );
        self.commit(msg);
    }

    /// Commits `offset + 1` for the message's partition (same as `commit_message`, which needs a
    /// borrowed message). A failed commit only means possible redelivery, which is idempotent.
    fn commit(&self, msg: &OwnedMessage) {
        if let Ok(mut finished) = self.finished.lock() {
            finished.insert((msg.topic().to_owned(), msg.partition()), msg.offset() + 1);
        }
        let mut tpl = TopicPartitionList::new();
        let result = tpl
            .add_partition_offset(
                msg.topic(),
                msg.partition(),
                Offset::Offset(msg.offset() + 1),
            )
            .and_then(|()| self.consumer.commit(&tpl, CommitMode::Async));
        if let Err(e) = result {
            warn!(
                error = %e,
                error_type = "KafkaCommitFailed",
                topic = msg.topic(),
                partition = msg.partition(),
                offset = msg.offset(),
                "offset commit failed; message may be redelivered"
            );
        }
    }

    /// Synchronously commits the offsets of all finished messages (shutdown only). Blocks this
    /// thread for one broker round-trip, acceptable once at shutdown. Deliberately not
    /// `commit_consumer_state`: with the default `enable.auto.offset.store=true` that commits the
    /// position after the last *received* message, i.e. past an abandoned in-flight message.
    fn flush_commits(&self) {
        let finished = match self.finished.lock() {
            Ok(f) => f.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        };
        if finished.is_empty() {
            return;
        }
        let mut tpl = TopicPartitionList::new();
        let result = finished
            .iter()
            .try_for_each(|((topic, partition), next)| {
                tpl.add_partition_offset(topic, *partition, Offset::Offset(*next))
            })
            .and_then(|()| self.consumer.commit(&tpl, CommitMode::Sync));
        if let Err(e) = result {
            warn!(
                error = %e,
                error_type = "KafkaCommitFailed",
                topic = %self.cfg.topic,
                "final offset commit on shutdown failed; finished messages may be redelivered"
            );
        }
    }
}

/// Span `"<topic> process"` (kind consumer) continuing the producer's trace from `traceparent`.
fn consumer_span(msg: &OwnedMessage) -> Span {
    let name = format!("{} process", msg.topic());
    let span = tracing::info_span!(
        "kafka.process",
        otel.name = %name,
        otel.kind = "consumer",
        messaging.system = "kafka",
        messaging.operation.type = "process",
        messaging.destination.name = msg.topic(),
        messaging.destination.partition.id = %msg.partition(),
        messaging.kafka.message.offset = msg.offset(),
    );
    let parent = opentelemetry::global::get_text_map_propagator(|p| {
        p.extract(&KafkaHeaderExtractor(msg.headers()))
    });
    // Fails only when the span is disabled by the active subscriber's filter; nothing to do.
    let _ = span.set_parent(parent);
    span
}

/// Reads W3C trace context (`traceparent`, `tracestate`) from Kafka message headers.
struct KafkaHeaderExtractor<'a>(Option<&'a OwnedHeaders>);

impl Extractor for KafkaHeaderExtractor<'_> {
    fn get(&self, key: &str) -> Option<&str> {
        self.0?
            .iter()
            .find(|h| h.key.eq_ignore_ascii_case(key))
            .and_then(|h| h.value)
            .and_then(|v| std::str::from_utf8(v).ok())
    }

    fn keys(&self) -> Vec<&str> {
        self.0
            .map_or_else(Vec::new, |hs| hs.iter().map(|h| h.key).collect())
    }
}

/// Sleeps for `d`; returns `false` (immediately) if `shutdown` is cancelled first.
async fn sleep_or_cancelled(d: Duration, shutdown: &CancellationToken) -> bool {
    tokio::select! {
        () = shutdown.cancelled() => false,
        () = tokio::time::sleep(d) => true,
    }
}

fn duration_ms(d: Duration) -> u64 {
    u64::try_from(d.as_millis()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rdkafka::message::Header;

    #[test]
    fn extractor_reads_headers_case_insensitively() {
        let headers = OwnedHeaders::new()
            .insert(Header {
                key: "event_id",
                value: Some("e-1"),
            })
            .insert(Header {
                key: "Traceparent",
                value: Some("00-abc-def-01"),
            });
        let ex = KafkaHeaderExtractor(Some(&headers));
        assert_eq!(ex.get("traceparent"), Some("00-abc-def-01"));
        assert_eq!(ex.get("missing"), None);
        assert_eq!(ex.keys(), vec!["event_id", "Traceparent"]);
        assert_eq!(KafkaHeaderExtractor(None).get("traceparent"), None);
    }

    #[test]
    fn config_new_defaults_max_backoff_to_30s() {
        let cfg = ConsumerConfig::new("k:9092", "g", "t", "c");
        assert_eq!(cfg.max_backoff, Duration::from_secs(30));
    }

    #[tokio::test]
    async fn backoff_sleep_returns_early_on_cancel() {
        let token = CancellationToken::new();
        token.cancel();
        let started = std::time::Instant::now();
        assert!(!sleep_or_cancelled(Duration::from_secs(30), &token).await);
        assert!(started.elapsed() < Duration::from_secs(1));
        assert!(sleep_or_cancelled(Duration::from_millis(1), &CancellationToken::new()).await);
    }

    #[test]
    fn malformed_event_found_through_context() {
        let e = anyhow::Error::from(MalformedEvent::new("data.total missing")).context("handling");
        let found = e.chain().find_map(|c| c.downcast_ref::<MalformedEvent>());
        assert_eq!(
            found.map(MalformedEvent::reason),
            Some("data.total missing")
        );
    }
}
