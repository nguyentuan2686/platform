//! Kafka messaging: event envelope and the idempotent consumer runner.
//!
//! A service implements [`EventHandler`] for one event type and runs it with [`run_consumer`].
//! Every message is processed in a database transaction that first records
//! `(consumer, event_id)` in `platform.processed_event`, so redelivered events are skipped and the
//! handler's own writes commit atomically with that marker.

pub mod consumer;
pub mod envelope;
mod idempotency;
pub(crate) mod metrics;

use async_trait::async_trait;
use sqlx::{Postgres, Transaction};

pub use consumer::{run_consumer, ConsumerConfig, DEFAULT_MAX_BACKOFF};
pub use envelope::Envelope;

/// Handles one event type inside the consumer's transaction.
#[async_trait]
pub trait EventHandler: Send + Sync {
    /// `event_type` this handler accepts; other event types on the topic are skipped.
    fn event_type(&self) -> &'static str;

    /// Applies the event using `tx` (committed by the runner together with the idempotency
    /// marker). Return [`MalformedEvent`] (possibly wrapped in context) for an event that can never
    /// succeed, e.g. `data` missing a required field: the runner logs it at WARN and skips it.
    /// Any other error is treated as transient and retried with backoff.
    ///
    /// Write only through `tx`: the runner inserts the idempotency marker in the same transaction
    /// and commits marker and handler writes together, so an event's effects are applied exactly
    /// once or not at all. Implementations must be cancel-safe: on shutdown an attempt can be
    /// dropped at any await point, which rolls `tx` back and leaves the message to be redelivered,
    /// so a handler must not have side effects outside `tx` that a dropped attempt would leave
    /// half-done.
    async fn handle(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        event: &Envelope,
    ) -> anyhow::Result<()>;
}

/// Permanent, non-retryable rejection of an event by a handler (see [`EventHandler::handle`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MalformedEvent {
    reason: String,
}

impl MalformedEvent {
    pub fn new(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
        }
    }

    pub fn reason(&self) -> &str {
        &self.reason
    }
}

impl std::fmt::Display for MalformedEvent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "malformed event: {}", self.reason)
    }
}

impl std::error::Error for MalformedEvent {}
