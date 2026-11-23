//! One attempt at processing an event exactly once per consumer.

use sqlx::PgPool;

use super::{Envelope, EventHandler};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Outcome {
    Processed,
    Duplicate,
}

/// Records `(consumer, event_id)` and runs the handler in the same transaction.
///
/// The marker insert and the handler's writes commit atomically: if the handler or the commit
/// fails, the marker is rolled back too and the event can be retried. A concurrent or earlier
/// commit of the same marker makes the insert affect 0 rows (after waiting for the other
/// transaction), which is reported as [`Outcome::Duplicate`].
pub(crate) async fn process_once(
    pool: &PgPool,
    consumer: &str,
    handler: &dyn EventHandler,
    event: &Envelope,
) -> anyhow::Result<Outcome> {
    let mut tx = pool.begin().await?;
    let inserted = sqlx::query(
        "INSERT INTO platform.processed_event (consumer, event_id) VALUES ($1, $2) \
         ON CONFLICT DO NOTHING",
    )
    .bind(consumer)
    .bind(event.event_id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if inserted == 0 {
        tx.rollback().await?;
        return Ok(Outcome::Duplicate);
    }
    handler.handle(&mut tx, event).await?;
    tx.commit().await?;
    Ok(Outcome::Processed)
}
