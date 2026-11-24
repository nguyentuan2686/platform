//! System clock and random ids.

use time::OffsetDateTime;
use uuid::Uuid;

use crate::application::{Clock, IdGenerator};

/// UTC now, truncated to microseconds (Postgres `timestamptz` precision) so a stored
/// notification reads back equal to the one recorded.
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> OffsetDateTime {
        let now = OffsetDateTime::now_utc();
        // Cannot fail: the truncated value is below the current nanosecond.
        now.replace_nanosecond(now.nanosecond() / 1_000 * 1_000)
            .unwrap_or(now)
    }
}

/// Random (v4) ids.
pub struct RandomIds;

impl IdGenerator for RandomIds {
    fn next_id(&self) -> Uuid {
        Uuid::new_v4()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clock_is_utc_with_microsecond_precision() {
        let now = SystemClock.now();
        assert!(now.offset().is_utc());
        assert_eq!(now.nanosecond() % 1_000, 0);
        assert_ne!(RandomIds.next_id(), RandomIds.next_id());
    }
}
