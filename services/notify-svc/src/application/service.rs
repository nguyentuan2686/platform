use std::sync::Arc;

use sqlx::{Postgres, Transaction};

use super::ports::{Clock, IdGenerator, NotificationRepositoryPort};
use crate::domain::{DomainError, Notification, OrderPlaced};

pub const DEFAULT_LIMIT: u32 = 50;
pub const MAX_LIMIT: u32 = 200;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error(transparent)]
    Domain(#[from] DomainError),
    #[error("limit must be at least 1")]
    InvalidLimit,
    #[error("repository failure")]
    Repository(#[source] anyhow::Error),
}

pub struct NotificationService<R> {
    repo: R,
    clock: Arc<dyn Clock>,
    ids: Arc<dyn IdGenerator>,
}

impl<R: NotificationRepositoryPort> NotificationService<R> {
    pub fn new(repo: R, clock: Arc<dyn Clock>, ids: Arc<dyn IdGenerator>) -> Self {
        Self { repo, clock, ids }
    }

    /// Pure construction step of `record`, unit-testable without a transaction.
    pub fn build(&self, e: &OrderPlaced) -> Result<Notification, AppError> {
        Ok(Notification::for_order_placed(
            e,
            self.ids.next_id(),
            self.clock.now(),
        )?)
    }

    pub async fn record(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        e: &OrderPlaced,
    ) -> Result<Notification, AppError> {
        let n = self.build(e)?;
        self.repo.save(tx, &n).await.map_err(AppError::Repository)?;
        Ok(n)
    }

    /// Defaults to 50; max 200 (larger values are clamped); 0 is invalid.
    pub async fn list(&self, limit: Option<u32>) -> Result<Vec<Notification>, AppError> {
        let limit = match limit {
            None => DEFAULT_LIMIT,
            Some(0) => return Err(AppError::InvalidLimit),
            Some(n) => n.min(MAX_LIMIT),
        };
        self.repo
            .list_recent(limit)
            .await
            .map_err(AppError::Repository)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use async_trait::async_trait;
    use time::OffsetDateTime;
    use uuid::Uuid;

    use super::*;

    struct FixedClock;
    impl Clock for FixedClock {
        fn now(&self) -> OffsetDateTime {
            OffsetDateTime::UNIX_EPOCH
        }
    }
    struct FixedIds;
    impl IdGenerator for FixedIds {
        fn next_id(&self) -> Uuid {
            Uuid::from_u128(42)
        }
    }

    #[derive(Default)]
    struct FakeRepo {
        limits: Mutex<Vec<u32>>,
        fail: bool,
    }
    #[async_trait]
    impl NotificationRepositoryPort for FakeRepo {
        async fn save(
            &self,
            _: &mut Transaction<'_, Postgres>,
            _: &Notification,
        ) -> anyhow::Result<()> {
            Ok(())
        }
        async fn list_recent(&self, limit: u32) -> anyhow::Result<Vec<Notification>> {
            if self.fail {
                anyhow::bail!("db down");
            }
            self.limits.lock().unwrap().push(limit);
            Ok(vec![])
        }
    }

    fn svc(repo: FakeRepo) -> NotificationService<FakeRepo> {
        NotificationService::new(repo, Arc::new(FixedClock), Arc::new(FixedIds))
    }

    fn event(total: &str) -> OrderPlaced {
        OrderPlaced {
            order_id: Uuid::from_u128(7),
            customer_id: "c".into(),
            total: total.into(),
            item_count: 3,
        }
    }

    #[test]
    fn build_uses_injected_clock_and_ids() {
        let n = svc(FakeRepo::default()).build(&event("25.50")).unwrap();
        assert_eq!(n.id(), Uuid::from_u128(42));
        assert_eq!(n.created_at(), OffsetDateTime::UNIX_EPOCH);
    }

    #[test]
    fn build_maps_domain_error() {
        let err = svc(FakeRepo::default()).build(&event("x")).unwrap_err();
        assert!(matches!(err, AppError::Domain(DomainError::InvalidTotal)));
    }

    #[tokio::test]
    async fn list_default_is_50() {
        let s = svc(FakeRepo::default());
        s.list(None).await.unwrap();
        assert_eq!(*s.repo.limits.lock().unwrap(), vec![50]);
    }

    #[tokio::test]
    async fn list_clamps_to_200_and_passes_valid() {
        let s = svc(FakeRepo::default());
        s.list(Some(1000)).await.unwrap();
        s.list(Some(200)).await.unwrap();
        s.list(Some(7)).await.unwrap();
        assert_eq!(*s.repo.limits.lock().unwrap(), vec![200, 200, 7]);
    }

    #[tokio::test]
    async fn list_zero_is_invalid() {
        let err = svc(FakeRepo::default()).list(Some(0)).await.unwrap_err();
        assert!(matches!(err, AppError::InvalidLimit));
    }

    #[tokio::test]
    async fn list_maps_repo_failure() {
        let repo = FakeRepo {
            fail: true,
            ..Default::default()
        };
        let err = svc(repo).list(None).await.unwrap_err();
        assert!(matches!(err, AppError::Repository(_)));
    }
}
