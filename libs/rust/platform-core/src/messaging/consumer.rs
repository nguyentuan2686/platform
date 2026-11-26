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
use std::time::{Duration, Instant};

use anyhow::Context as _;
use opentelemetry::propagation::Extractor;
use rdkafka::consumer::{CommitMode, Consumer as _, StreamConsumer};
use rdkafka::error::{KafkaError, RDKafkaErrorCode};
use rdkafka::message::{Headers as _, OwnedHeaders, OwnedMessage};
use rdkafka::{ClientConfig, Message as _, Offset, TopicPartitionList};
use sqlx::PgPool;
use tokio_util::sync::CancellationToken;
use tracing::{debug, error, info, warn, Instrument as _, Span};
use tracing_opentelemetry::OpenTelemetrySpanExt as _;

use super::idempotency::{process_once, Outcome};
use super::metrics::{ConsumerMetrics, ProcessOutcome};
use super::{Envelope, EventHandler, MalformedEvent};

/// Default upper bound for the processing retry backoff (a receive error uses the short `RECV_ERROR_PAUSE`).
pub const DEFAULT_MAX_BACKOFF: Duration = Duration::from_secs(30);
const INITIAL_BACKOFF: Duration = Duration::from_secs(1);
/// Pause after a receive error (broker down, transport failure). librdkafka reconnects on its own, paced by
/// `reconnect.backoff.ms`..`reconnect.backoff.max.ms`, and queues one error event per failed attempt; `recv()` blocks
/// until the next event, so a persistent outage is already paced. An app-level exponential backoff here would drain
/// a burst of stale queued errors at one per backoff period (minutes at 30s) after the broker is back. This pause
/// only guards against a hot loop.
const RECV_ERROR_PAUSE: Duration = Duration::from_millis(200);
// Compile-time guard: a burst of stale errors queued during an outage must drain in seconds once the broker is back,
// so the pause stays short and below the processing backoff (it must never become the 1s..30s processing backoff).
const _: () = assert!(
    RECV_ERROR_PAUSE.as_millis() > 0 && RECV_ERROR_PAUSE.as_millis() < INITIAL_BACKOFF.as_millis()
);

#[derive(Debug, Clone)]
pub struct ConsumerConfig {
    pub bootstrap_servers: String,
    pub group_id: String,
    pub topic: String,
    /// Stable name of this consumer; part of the idempotency key `(consumer, event_id)`.
    pub consumer_name: &'static str,
    /// Processing retry backoff starts at 1s and doubles up to this value (not used for receive errors).
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
        // A subscribed topic that does not exist yet (fresh cluster: the producer creates it at its
        // own startup) is only re-checked on this refresh; librdkafka's 5 min default left the
        // consumer idle that long. 10 s is cheap on a small cluster.
        .set("topic.metadata.refresh.interval.ms", "10000")
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
        metrics: ConsumerMetrics::global(),
    };
    let mut recv_errors = RecvErrorGuard::default();
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
                recv_errors.on_message();
                let span = consumer_span(&msg);
                if runner.process(&msg).instrument(span).await == Flow::Stop {
                    break;
                }
            }
            Err(e) => {
                let kind = classify_recv_error(&e);
                let decision = recv_errors.on_error(Instant::now(), kind);
                if decision.give_up {
                    error!(
                        error = %e,
                        error_type = "KafkaReceiveGaveUp",
                        error_kind = ?kind,
                        topic = %cfg.topic,
                        "kafka receive keeps failing; stopping the consumer"
                    );
                    runner.flush_commits();
                    anyhow::bail!("kafka receive keeps failing ({kind:?}): {e}");
                }
                if let Some(suppressed) = decision.log {
                    warn!(
                        error = %e,
                        error_type = "KafkaReceiveFailed",
                        error_kind = ?kind,
                        topic = %cfg.topic,
                        backoff_ms = duration_ms(RECV_ERROR_PAUSE),
                        suppressed,
                        "kafka receive failed, retrying"
                    );
                }
                if !sleep_or_cancelled(RECV_ERROR_PAUSE, &shutdown).await {
                    break;
                }
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
    metrics: ConsumerMetrics,
}

impl Runner<'_> {
    async fn process(&self, msg: &OwnedMessage) -> Flow {
        let started = Instant::now();
        let envelope = match msg.payload().map(Envelope::parse) {
            Some(Ok(e)) => e,
            Some(Err(e)) => {
                self.skip_malformed(msg, &e.to_string(), started);
                return Flow::Continue;
            }
            None => {
                self.skip_malformed(msg, "empty payload", started);
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
            self.record(ProcessOutcome::Ignored, started);
            self.commit(msg);
            return Flow::Continue;
        }

        let mut backoff = INITIAL_BACKOFF.min(self.cfg.max_backoff);
        loop {
            let attempt = Instant::now();
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
                    let (what, label) = match outcome {
                        Outcome::Processed => ("event processed", ProcessOutcome::Processed),
                        Outcome::Duplicate => {
                            ("duplicate event skipped", ProcessOutcome::Duplicate)
                        }
                    };
                    info!(
                        topic = msg.topic(),
                        partition = msg.partition(),
                        offset = msg.offset(),
                        event_id = %envelope.event_id,
                        event_type = %envelope.event_type,
                        "{what}"
                    );
                    self.record(label, attempt);
                    self.commit(msg);
                    return Flow::Continue;
                }
                Err(e) => {
                    if let Some(m) = e.chain().find_map(|c| c.downcast_ref::<MalformedEvent>()) {
                        self.skip_malformed(msg, m.reason(), attempt);
                        return Flow::Continue;
                    }
                    self.record(ProcessOutcome::Failed, attempt);
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

    fn record(&self, outcome: ProcessOutcome, started: Instant) {
        self.metrics.record(
            &self.cfg.topic,
            &self.cfg.group_id,
            outcome,
            started.elapsed(),
        );
    }

    /// WARN (never the payload) and commit past the message: it can never succeed.
    fn skip_malformed(&self, msg: &OwnedMessage, reason: &str, started: Instant) {
        self.record(ProcessOutcome::Malformed, started);
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
    /// Last matching header wins, like Kafka's Java `Headers.lastHeader`.
    fn get(&self, key: &str) -> Option<&str> {
        self.0?
            .iter()
            .filter(|h| h.key.eq_ignore_ascii_case(key))
            .last()
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

/// While receive errors keep coming with no message in between, at most one WARN per this period (the first error
/// is always logged); the next WARN carries the number of errors suppressed since the last one.
const RECV_ERROR_LOG_EVERY: Duration = Duration::from_secs(30);
/// A non-transport receive error (authorization, group, configuration) that persists this long with no message in
/// between makes `run_consumer` return an error, so the service's supervisor restarts the client or the process.
const RECV_ERROR_GIVE_UP_AFTER: Duration = Duration::from_secs(600);

/// How a receive error is handled: `Transport` (broker unreachable, coordinator moving, topic not created yet) is
/// retried for as long as it lasts, librdkafka reconnects by itself; `Fatal` (the client is unusable) stops the
/// consumer at once; `Other` stops it after [`RECV_ERROR_GIVE_UP_AFTER`] without a message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RecvErrorKind {
    Transport,
    Fatal,
    Other,
}

fn classify_recv_error(e: &KafkaError) -> RecvErrorKind {
    use RDKafkaErrorCode as C;
    if matches!(e, KafkaError::MessageConsumptionFatal(_)) {
        return RecvErrorKind::Fatal;
    }
    match e.rdkafka_error_code() {
        Some(
            C::BrokerTransportFailure
            | C::AllBrokersDown
            | C::Resolve
            | C::BrokerNotAvailable
            | C::NetworkException
            | C::RequestTimedOut
            | C::OperationTimedOut
            | C::LeaderNotAvailable
            | C::NotCoordinator
            | C::CoordinatorNotAvailable
            | C::CoordinatorLoadInProgress
            | C::UnknownTopicOrPartition
            | C::UnknownTopic,
        ) => RecvErrorKind::Transport,
        _ => RecvErrorKind::Other,
    }
}

#[derive(Debug, PartialEq, Eq)]
struct RecvErrorDecision {
    /// `Some(n)`: log this error, `n` errors were suppressed since the previous log line.
    log: Option<u64>,
    give_up: bool,
}

/// Rate-limits receive-error logs and decides when to stop; reset by every received message.
#[derive(Debug, Default)]
struct RecvErrorGuard {
    first_other: Option<Instant>,
    last_log: Option<Instant>,
    suppressed: u64,
}

impl RecvErrorGuard {
    fn on_error(&mut self, now: Instant, kind: RecvErrorKind) -> RecvErrorDecision {
        let give_up = match kind {
            RecvErrorKind::Fatal => true,
            RecvErrorKind::Transport => false,
            RecvErrorKind::Other => {
                let first = *self.first_other.get_or_insert(now);
                now.duration_since(first) >= RECV_ERROR_GIVE_UP_AFTER
            }
        };
        let due = self
            .last_log
            .is_none_or(|t| now.duration_since(t) >= RECV_ERROR_LOG_EVERY);
        let log = if give_up || due {
            self.last_log = Some(now);
            Some(std::mem::take(&mut self.suppressed))
        } else {
            self.suppressed += 1;
            None
        };
        RecvErrorDecision { log, give_up }
    }

    fn on_message(&mut self) {
        *self = Self::default();
    }
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
    fn extractor_uses_last_header_when_repeated() {
        // Kafka/Java (`Headers.lastHeader`) take the last value of a repeated header.
        let headers = OwnedHeaders::new()
            .insert(Header {
                key: "traceparent",
                value: Some("00-first-01"),
            })
            .insert(Header {
                key: "traceparent",
                value: Some("00-last-01"),
            });
        assert_eq!(
            KafkaHeaderExtractor(Some(&headers)).get("traceparent"),
            Some("00-last-01")
        );
    }

    #[test]
    fn recv_errors_are_classified() {
        use rdkafka::error::{KafkaError, RDKafkaErrorCode as C};
        assert_eq!(
            classify_recv_error(&KafkaError::MessageConsumption(C::AllBrokersDown)),
            RecvErrorKind::Transport
        );
        assert_eq!(
            classify_recv_error(&KafkaError::MessageConsumption(C::BrokerTransportFailure)),
            RecvErrorKind::Transport
        );
        assert_eq!(
            classify_recv_error(&KafkaError::MessageConsumptionFatal(C::Fail)),
            RecvErrorKind::Fatal
        );
        assert_eq!(
            classify_recv_error(&KafkaError::MessageConsumption(C::TopicAuthorizationFailed)),
            RecvErrorKind::Other
        );
    }

    #[test]
    fn recv_error_logs_are_rate_limited_with_a_suppressed_count() {
        let t0 = Instant::now();
        let mut g = RecvErrorGuard::default();
        assert_eq!(g.on_error(t0, RecvErrorKind::Transport).log, Some(0));
        for i in 1..=50 {
            let d = g.on_error(
                t0 + Duration::from_millis(200 * i),
                RecvErrorKind::Transport,
            );
            assert_eq!(
                d.log, None,
                "error {i} within the log period must be suppressed"
            );
        }
        assert_eq!(
            g.on_error(t0 + RECV_ERROR_LOG_EVERY, RecvErrorKind::Transport)
                .log,
            Some(50)
        );
    }

    #[test]
    fn transport_errors_never_give_up() {
        let t0 = Instant::now();
        let mut g = RecvErrorGuard::default();
        for m in 0..120 {
            let d = g.on_error(t0 + Duration::from_secs(60 * m), RecvErrorKind::Transport);
            assert!(
                !d.give_up,
                "a broker outage of {m} min must not stop the consumer"
            );
        }
    }

    #[test]
    fn fatal_error_gives_up_at_once_and_is_logged() {
        let mut g = RecvErrorGuard::default();
        let t0 = Instant::now();
        let _ = g.on_error(t0, RecvErrorKind::Transport);
        let d = g.on_error(t0 + Duration::from_millis(200), RecvErrorKind::Fatal);
        assert!(d.give_up);
        assert!(d.log.is_some(), "the give-up error is always logged");
    }

    #[test]
    fn persistent_other_error_gives_up_after_the_limit_and_a_message_resets() {
        let t0 = Instant::now();
        let mut g = RecvErrorGuard::default();
        assert!(!g.on_error(t0, RecvErrorKind::Other).give_up);
        let just_before = t0 + RECV_ERROR_GIVE_UP_AFTER.saturating_sub(Duration::from_secs(1));
        assert!(!g.on_error(just_before, RecvErrorKind::Other).give_up);
        g.on_message();
        assert!(
            !g.on_error(t0 + RECV_ERROR_GIVE_UP_AFTER, RecvErrorKind::Other)
                .give_up,
            "reset by a message"
        );
        let later = t0 + RECV_ERROR_GIVE_UP_AFTER * 2;
        assert!(g.on_error(later, RecvErrorKind::Other).give_up);
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
