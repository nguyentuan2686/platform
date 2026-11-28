//! Telegram use cases: record a top-up (and queue its message), deliver the outbox, queue the daily summary.

use std::sync::Arc;

use sqlx::{Postgres, Transaction};
use time::{Duration, OffsetDateTime};

use super::ports::{
    Clock, DayMetricsPort, MessengerPort, OutboxItem, OutboxPort, PaymentRepositoryPort,
};
use crate::domain::{day_range, due_day, DailySummary, PaymentSucceeded, TopUp, TopUpError};

/// A message still unsent after this long is dropped instead of sent late (e.g. Telegram configured much later).
pub const OUTBOX_MAX_AGE: Duration = Duration::hours(24);
/// First retry delay; doubled per failed attempt up to [`MAX_RETRY_DELAY`].
pub const FIRST_RETRY_DELAY: Duration = Duration::seconds(30);
pub const MAX_RETRY_DELAY: Duration = Duration::hours(1);
/// How long after the day ends the summary waits for Prometheus/Grafana before it is sent with gaps.
pub const SUMMARY_GRACE: Duration = Duration::minutes(30);
/// Messages delivered per sender pass.
pub const SEND_BATCH: u32 = 20;

#[derive(Debug, thiserror::Error)]
pub enum TelegramError {
    #[error(transparent)]
    TopUp(#[from] TopUpError),
    #[error("repository failure")]
    Repository(#[source] anyhow::Error),
}

/// What recording a top-up did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Recorded {
    /// Stored and its message queued.
    Announced,
    /// Stored for revenue only: paid too long ago to announce now.
    Stored,
    /// The order was already stored.
    Duplicate,
}

pub struct TopUpService<P> {
    repo: P,
    clock: Arc<dyn Clock>,
}

impl<P: PaymentRepositoryPort> TopUpService<P> {
    pub fn new(repo: P, clock: Arc<dyn Clock>) -> Self {
        Self { repo, clock }
    }

    /// Pure step of [`Self::record`]: the top-up and the text to queue, if it is recent enough to announce.
    pub fn plan(&self, e: &PaymentSucceeded) -> Result<(TopUp, Option<String>), TopUpError> {
        let t = TopUp::from_event(e)?;
        let text = t.announce_at(self.clock.now()).then(|| t.message());
        Ok((t, text))
    }

    pub async fn record(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        e: &PaymentSucceeded,
    ) -> Result<Recorded, TelegramError> {
        let (t, text) = self.plan(e)?;
        let inserted = self
            .repo
            .save(tx, &t, text.as_deref(), self.clock.now())
            .await
            .map_err(TelegramError::Repository)?;
        Ok(match (inserted, text) {
            (false, _) => Recorded::Duplicate,
            (true, Some(_)) => Recorded::Announced,
            (true, None) => Recorded::Stored,
        })
    }
}

/// Counts of one delivery pass.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Delivery {
    pub sent: u32,
    pub failed: u32,
    pub expired: u32,
}

/// Delivers due outbox messages. Without a messenger (Telegram not configured) it does nothing and the rows wait;
/// rows older than [`OUTBOX_MAX_AGE`] are then expired instead of sent late.
pub struct OutboxSender<O> {
    outbox: O,
    messenger: Option<Arc<dyn MessengerPort>>,
    clock: Arc<dyn Clock>,
}

/// Delay before the attempt after `attempts` failed ones.
#[must_use]
pub fn retry_delay(attempts: i32) -> Duration {
    let shift = u32::try_from(attempts.clamp(1, 12) - 1).unwrap_or(0);
    let d = FIRST_RETRY_DELAY * 2_i32.pow(shift);
    if d > MAX_RETRY_DELAY {
        MAX_RETRY_DELAY
    } else {
        d
    }
}

impl<O: OutboxPort> OutboxSender<O> {
    pub fn new(
        outbox: O,
        messenger: Option<Arc<dyn MessengerPort>>,
        clock: Arc<dyn Clock>,
    ) -> Self {
        Self {
            outbox,
            messenger,
            clock,
        }
    }

    #[must_use]
    pub fn enabled(&self) -> bool {
        self.messenger.is_some()
    }

    /// One pass over the due messages. A send failure reschedules that message and continues with the next.
    pub async fn deliver_due(&self) -> anyhow::Result<Delivery> {
        let Some(messenger) = &self.messenger else {
            return Ok(Delivery::default());
        };
        let now = self.clock.now();
        let mut d = Delivery::default();
        for item in self.outbox.due(now, SEND_BATCH).await? {
            if now - item.created_at > OUTBOX_MAX_AGE {
                self.outbox.mark_expired(item.id).await?;
                d.expired += 1;
                continue;
            }
            match messenger.send(&item.text).await {
                Ok(()) => {
                    self.outbox.mark_sent(item.id, now).await?;
                    d.sent += 1;
                }
                Err(e) => {
                    self.reschedule(&item, now, &e).await?;
                    d.failed += 1;
                }
            }
        }
        Ok(d)
    }

    async fn reschedule(
        &self,
        item: &OutboxItem,
        now: OffsetDateTime,
        e: &anyhow::Error,
    ) -> anyhow::Result<()> {
        let attempts = item.attempts.saturating_add(1);
        tracing::warn!(
            outbox_id = item.id,
            attempts,
            error = %format!("{e:#}"),
            error_type = "TelegramSendFailed",
            "telegram send failed; retrying later"
        );
        self.outbox
            .retry_later(item.id, attempts, now + retry_delay(attempts))
            .await
    }
}

/// What a summary tick did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SummaryTick {
    AlreadyQueued,
    /// Prometheus or Grafana did not answer and the grace period is not over: try again next tick.
    Waiting,
    Queued,
}

pub struct SummaryService<P, O> {
    payments: P,
    outbox: O,
    metrics: Arc<dyn DayMetricsPort>,
    clock: Arc<dyn Clock>,
}

impl<P: PaymentRepositoryPort, O: OutboxPort> SummaryService<P, O> {
    pub fn new(
        payments: P,
        outbox: O,
        metrics: Arc<dyn DayMetricsPort>,
        clock: Arc<dyn Clock>,
    ) -> Self {
        Self {
            payments,
            outbox,
            metrics,
            clock,
        }
    }

    /// Queues the summary of the due day once (outbox key `summary:<day>`).
    pub async fn tick(&self) -> anyhow::Result<SummaryTick> {
        let now = self.clock.now();
        let day = due_day(now);
        let key = format!("summary:{day}");
        if self.outbox.contains(&key).await? {
            return Ok(SummaryTick::AlreadyQueued);
        }
        let (from, end) = day_range(day);
        // At 23:59 the day is not over yet: count up to now.
        let to = if now < end { now } else { end };
        let revenue = self.payments.revenue(from, to).await?;
        let uses = self.metrics.uses(from, to).await;
        let error_rates = self.metrics.error_rates(from, to).await;
        let alerts = self.metrics.alerts_fired(from, to).await;
        let any_failed = uses.is_err() || error_rates.is_err() || alerts.is_err();
        if any_failed && now < end + SUMMARY_GRACE {
            return Ok(SummaryTick::Waiting);
        }
        for e in [
            uses.as_ref().err(),
            error_rates.as_ref().err(),
            alerts.as_ref().err(),
        ]
        .into_iter()
        .flatten()
        {
            tracing::warn!(
                error = %format!("{e:#}"),
                error_type = "SummaryMetricUnavailable",
                %day,
                "daily summary sent without one section"
            );
        }
        let summary = DailySummary {
            day,
            revenue,
            uses: uses.ok(),
            error_rates: error_rates.ok(),
            alerts_fired: alerts.ok(),
        };
        self.outbox.enqueue(&key, &summary.message(), now).await?;
        Ok(SummaryTick::Queued)
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::sync::Mutex;

    use async_trait::async_trait;
    use time::macros::datetime;
    use uuid::Uuid;

    use super::*;
    use crate::domain::{ErrorRate, Money, RevenueLine, Uses};

    struct At(Mutex<OffsetDateTime>);
    impl Clock for At {
        fn now(&self) -> OffsetDateTime {
            *self.0.lock().unwrap()
        }
    }
    fn at(t: OffsetDateTime) -> Arc<At> {
        Arc::new(At(Mutex::new(t)))
    }

    #[derive(Default)]
    struct Row {
        text: String,
        status: &'static str,
        attempts: i32,
        next: Option<OffsetDateTime>,
        created_at: Option<OffsetDateTime>,
    }

    /// In-memory outbox + payments (keyed like the tables).
    #[derive(Default)]
    struct Store {
        rows: Mutex<BTreeMap<String, Row>>,
        revenue: Vec<RevenueLine>,
        ranges: Mutex<Vec<(OffsetDateTime, OffsetDateTime)>>,
    }
    impl Store {
        fn id_of(key: &str) -> i64 {
            i64::try_from(key.len()).unwrap()
        }
    }
    #[async_trait]
    impl OutboxPort for Arc<Store> {
        async fn contains(&self, key: &str) -> anyhow::Result<bool> {
            Ok(self.rows.lock().unwrap().contains_key(key))
        }
        async fn enqueue(
            &self,
            key: &str,
            text: &str,
            now: OffsetDateTime,
        ) -> anyhow::Result<bool> {
            let mut rows = self.rows.lock().unwrap();
            if rows.contains_key(key) {
                return Ok(false);
            }
            rows.insert(
                key.into(),
                Row {
                    text: text.into(),
                    status: "pending",
                    created_at: Some(now),
                    next: Some(now),
                    ..Row::default()
                },
            );
            Ok(true)
        }
        async fn due(&self, now: OffsetDateTime, _: u32) -> anyhow::Result<Vec<OutboxItem>> {
            Ok(self
                .rows
                .lock()
                .unwrap()
                .iter()
                .filter(|(_, r)| r.status == "pending" && r.next.is_some_and(|n| n <= now))
                .map(|(k, r)| OutboxItem {
                    id: Store::id_of(k),
                    text: r.text.clone(),
                    attempts: r.attempts,
                    created_at: r.created_at.unwrap(),
                })
                .collect())
        }
        async fn mark_sent(&self, id: i64, _: OffsetDateTime) -> anyhow::Result<()> {
            self.set(id, |r| r.status = "sent");
            Ok(())
        }
        async fn mark_expired(&self, id: i64) -> anyhow::Result<()> {
            self.set(id, |r| r.status = "expired");
            Ok(())
        }
        async fn retry_later(
            &self,
            id: i64,
            attempts: i32,
            next: OffsetDateTime,
        ) -> anyhow::Result<()> {
            self.set(id, |r| {
                r.attempts = attempts;
                r.next = Some(next);
            });
            Ok(())
        }
    }
    impl Store {
        fn set(&self, id: i64, f: impl Fn(&mut Row)) {
            for (k, r) in self.rows.lock().unwrap().iter_mut() {
                if Store::id_of(k) == id {
                    f(r);
                }
            }
        }
        fn status(&self, key: &str) -> &'static str {
            self.rows.lock().unwrap()[key].status
        }
    }
    #[async_trait]
    impl PaymentRepositoryPort for Arc<Store> {
        async fn save(
            &self,
            _: &mut Transaction<'_, Postgres>,
            _: &TopUp,
            _: Option<&str>,
            _: OffsetDateTime,
        ) -> anyhow::Result<bool> {
            unreachable!("needs a transaction: covered by the Postgres adapter test")
        }
        async fn revenue(
            &self,
            from: OffsetDateTime,
            to: OffsetDateTime,
        ) -> anyhow::Result<Vec<RevenueLine>> {
            self.ranges.lock().unwrap().push((from, to));
            Ok(self.revenue.clone())
        }
    }

    #[derive(Default)]
    struct Phone {
        sent: Mutex<Vec<String>>,
        fail: bool,
    }
    #[async_trait]
    impl MessengerPort for Phone {
        async fn send(&self, text: &str) -> anyhow::Result<()> {
            if self.fail {
                anyhow::bail!("telegram 502");
            }
            self.sent.lock().unwrap().push(text.into());
            Ok(())
        }
    }

    struct Metrics {
        ok: bool,
    }
    #[async_trait]
    impl DayMetricsPort for Metrics {
        async fn uses(&self, _: OffsetDateTime, _: OffsetDateTime) -> anyhow::Result<Uses> {
            if self.ok {
                Ok(Uses {
                    basic_readings: 3,
                    qa: 1,
                    number_checks: 2,
                })
            } else {
                anyhow::bail!("prometheus down")
            }
        }
        async fn error_rates(
            &self,
            _: OffsetDateTime,
            _: OffsetDateTime,
        ) -> anyhow::Result<Vec<ErrorRate>> {
            Ok(vec![])
        }
        async fn alerts_fired(&self, _: OffsetDateTime, _: OffsetDateTime) -> anyhow::Result<u64> {
            Ok(1)
        }
    }

    fn payment(paid_at: OffsetDateTime) -> PaymentSucceeded {
        PaymentSucceeded {
            order_id: Uuid::from_u128(1),
            product_code: "CREDIT_5".into(),
            amount: "50000.00".into(),
            currency: "VND".into(),
            paid_at,
        }
    }

    #[test]
    fn recent_topups_get_a_message_old_ones_do_not() {
        let now = datetime!(2026-11-28 07:30 UTC);
        let s = TopUpService::new(Arc::new(Store::default()), at(now));
        let (t, text) = s.plan(&payment(datetime!(2026-11-28 07:05 UTC))).unwrap();
        assert_eq!(t.money.display(), "50.000đ");
        assert!(text.unwrap().starts_with("💰 Nạp tiền\n"));
        let (_, text) = s.plan(&payment(datetime!(2026-11-28 05:00 UTC))).unwrap();
        assert_eq!(text, None, "a replayed old event is not announced");
        let bad = PaymentSucceeded {
            amount: "5".into(),
            ..payment(now)
        };
        assert!(matches!(s.plan(&bad), Err(TopUpError::InvalidAmount)));
    }

    #[test]
    fn retry_delay_doubles_up_to_an_hour() {
        assert_eq!(retry_delay(1), Duration::seconds(30));
        assert_eq!(retry_delay(2), Duration::seconds(60));
        assert_eq!(retry_delay(4), Duration::seconds(240));
        assert_eq!(retry_delay(8), MAX_RETRY_DELAY);
        assert_eq!(retry_delay(100), MAX_RETRY_DELAY);
    }

    #[tokio::test]
    async fn sender_delivers_retries_and_expires() {
        let now = datetime!(2026-11-28 12:00 UTC);
        let store = Arc::new(Store::default());
        store.enqueue("payment:a", "hi", now).await.unwrap();
        store
            .enqueue("payment:old", "stale", now - Duration::hours(25))
            .await
            .unwrap();

        let failing = OutboxSender::new(
            store.clone(),
            Some(Arc::new(Phone {
                fail: true,
                ..Phone::default()
            })),
            at(now),
        );
        assert_eq!(
            failing.deliver_due().await.unwrap(),
            Delivery {
                sent: 0,
                failed: 1,
                expired: 1
            }
        );
        assert_eq!(store.status("payment:old"), "expired");
        assert_eq!(
            store.rows.lock().unwrap()["payment:a"].next,
            Some(now + Duration::seconds(30))
        );

        let phone = Arc::new(Phone::default());
        let clock = at(now + Duration::seconds(10));
        let sender = OutboxSender::new(
            store.clone(),
            Some(phone.clone() as Arc<dyn MessengerPort>),
            clock.clone(),
        );
        assert_eq!(
            sender.deliver_due().await.unwrap(),
            Delivery::default(),
            "not due before its retry time"
        );
        *clock.0.lock().unwrap() = now + Duration::seconds(31);
        assert_eq!(
            sender.deliver_due().await.unwrap(),
            Delivery {
                sent: 1,
                failed: 0,
                expired: 0
            }
        );
        assert_eq!(*phone.sent.lock().unwrap(), vec!["hi".to_owned()]);
        assert_eq!(store.status("payment:a"), "sent");
    }

    #[tokio::test]
    async fn sender_without_telegram_leaves_messages_waiting() {
        let now = datetime!(2026-11-28 12:00 UTC);
        let store = Arc::new(Store::default());
        store.enqueue("payment:a", "hi", now).await.unwrap();
        let sender = OutboxSender::new(store.clone(), None, at(now));
        assert!(!sender.enabled());
        assert_eq!(sender.deliver_due().await.unwrap(), Delivery::default());
        assert_eq!(store.status("payment:a"), "pending");
    }

    fn summary(
        store: &Arc<Store>,
        now: OffsetDateTime,
        ok: bool,
    ) -> SummaryService<Arc<Store>, Arc<Store>> {
        SummaryService::new(
            store.clone(),
            store.clone(),
            Arc::new(Metrics { ok }),
            at(now),
        )
    }

    #[tokio::test]
    async fn summary_is_queued_once_per_day_up_to_now() {
        let store = Arc::new(Store {
            revenue: vec![RevenueLine {
                product_code: "CREDIT_1".into(),
                count: 1,
                total: Money {
                    minor: 1_000_000,
                    currency: "VND".into(),
                },
            }],
            ..Store::default()
        });
        let at_2359 = datetime!(2026-11-28 16:59 UTC); // 23:59 VN
        assert_eq!(
            summary(&store, at_2359, true).tick().await.unwrap(),
            SummaryTick::Queued
        );
        assert_eq!(
            summary(&store, at_2359 + Duration::minutes(5), true)
                .tick()
                .await
                .unwrap(),
            SummaryTick::AlreadyQueued
        );
        assert_eq!(
            *store.ranges.lock().unwrap(),
            vec![(datetime!(2026-11-27 17:00 UTC), at_2359)]
        );
        let text = store.rows.lock().unwrap()["summary:2026-11-28"]
            .text
            .clone();
        assert!(
            text.starts_with("📊 Tổng kết 28/11/2026\nDoanh thu: 10.000đ (1 lần nạp)"),
            "{text}"
        );
        assert!(
            text.contains("Lượt dùng: luận giải 3, hỏi đáp 1, xem số 2"),
            "{text}"
        );
    }

    #[tokio::test]
    async fn summary_waits_for_metrics_then_sends_with_a_gap() {
        let store = Arc::new(Store::default());
        let at_2359 = datetime!(2026-11-28 16:59 UTC);
        assert_eq!(
            summary(&store, at_2359, false).tick().await.unwrap(),
            SummaryTick::Waiting
        );
        assert!(!store.contains("summary:2026-11-28").await.unwrap());
        // 00:31 VN, past the grace period: sent without that section, full day counted
        let late = datetime!(2026-11-28 17:31 UTC);
        assert_eq!(
            summary(&store, late, false).tick().await.unwrap(),
            SummaryTick::Queued
        );
        assert_eq!(
            store.ranges.lock().unwrap().last(),
            Some(&(
                datetime!(2026-11-27 17:00 UTC),
                datetime!(2026-11-28 17:00 UTC)
            ))
        );
        assert!(store.rows.lock().unwrap()["summary:2026-11-28"]
            .text
            .contains("Lượt dùng: không lấy được"));
    }
}
