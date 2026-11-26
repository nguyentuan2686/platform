//! Consumer RED metrics: `messaging.process.duration` histogram per handled message.

use std::time::Duration;

use opentelemetry::metrics::{Histogram, Meter};
use opentelemetry::KeyValue;

use crate::http::DURATION_BUCKETS_S;

/// Result of handling one message; the `outcome` label value (bounded set).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ProcessOutcome {
    Processed,
    Duplicate,
    Malformed,
    Ignored,
    Failed,
}

impl ProcessOutcome {
    fn as_str(self) -> &'static str {
        match self {
            Self::Processed => "processed",
            Self::Duplicate => "duplicate",
            Self::Malformed => "malformed",
            Self::Ignored => "ignored",
            Self::Failed => "failed",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ConsumerMetrics {
    duration: Histogram<f64>,
}

impl ConsumerMetrics {
    pub(crate) fn from_meter(meter: &Meter) -> Self {
        let duration = meter
            .f64_histogram("messaging.process.duration")
            .with_unit("s")
            .with_description("Duration of processing one consumed message")
            .with_boundaries(DURATION_BUCKETS_S.to_vec())
            .build();
        Self { duration }
    }

    pub(crate) fn global() -> Self {
        Self::from_meter(&opentelemetry::global::meter("platform-core"))
    }

    pub(crate) fn record(
        &self,
        topic: &str,
        group: &str,
        outcome: ProcessOutcome,
        elapsed: Duration,
    ) {
        self.duration.record(
            elapsed.as_secs_f64(),
            &[
                KeyValue::new("messaging.destination.name", topic.to_owned()),
                KeyValue::new("messaging.consumer.group.name", group.to_owned()),
                KeyValue::new("outcome", outcome.as_str()),
            ],
        );
    }
}
