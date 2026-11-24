//! Chart use cases.

use crate::application::birth::NormalizedBirth;
use crate::application::ports::ZiweiEngine;
use crate::domain::chart::{Chart, Flow, Gender};
use time::Date;

/// Who the chart is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChartRequest {
    pub birth: NormalizedBirth,
    pub gender: Gender,
}

#[derive(Debug, thiserror::Error)]
pub enum ChartError {
    /// The engine failed; the cause is the source.
    #[error("chart engine failed")]
    Engine(#[source] anyhow::Error),
}

/// What the HTTP adapter needs from the chart use cases (object safe, so tests can inject a
/// failing engine).
pub trait ChartUseCases: Send + Sync {
    /// The natal chart.
    fn chart(&self, req: &ChartRequest) -> Result<Chart, ChartError>;
    /// The flow of `date` for the natal chart.
    fn daily(&self, req: &ChartRequest, date: Date) -> Result<Flow, ChartError>;
}

/// Casts charts and daily flows through a [`ZiweiEngine`].
pub struct ChartService<E: ZiweiEngine> {
    engine: E,
}

impl<E: ZiweiEngine> ChartService<E> {
    pub fn new(engine: E) -> Self {
        Self { engine }
    }

    /// The natal chart.
    pub fn chart(&self, req: &ChartRequest) -> Result<Chart, ChartError> {
        self.engine
            .chart(&req.birth, req.gender)
            .map_err(ChartError::Engine)
    }

    /// The flow of `date` for the natal chart.
    pub fn daily(&self, req: &ChartRequest, date: Date) -> Result<Flow, ChartError> {
        self.engine
            .flow(&req.birth, req.gender, date)
            .map_err(ChartError::Engine)
    }
}

impl<E: ZiweiEngine + Send + Sync> ChartUseCases for ChartService<E> {
    fn chart(&self, req: &ChartRequest) -> Result<Chart, ChartError> {
        ChartService::chart(self, req)
    }

    fn daily(&self, req: &ChartRequest, date: Date) -> Result<Flow, ChartError> {
        ChartService::daily(self, req, date)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::calendar::{Branch, CalendarKind};
    use time::Month;

    /// Fails every call, recording nothing: the service must wrap the cause.
    struct FailingEngine;

    impl ZiweiEngine for FailingEngine {
        fn chart(&self, _: &NormalizedBirth, _: Gender) -> anyhow::Result<Chart> {
            Err(anyhow::anyhow!("boom"))
        }

        fn flow(&self, _: &NormalizedBirth, _: Gender, _: Date) -> anyhow::Result<Flow> {
            Err(anyhow::anyhow!("boom"))
        }
    }

    fn req() -> ChartRequest {
        let d = Date::from_calendar_date(1994, Month::March, 12).unwrap();
        ChartRequest {
            birth: NormalizedBirth::from_parts(d, Branch::Chen, CalendarKind::Vn),
            gender: Gender::Male,
        }
    }

    #[test]
    fn engine_failures_become_typed_errors_with_the_cause_as_source() {
        let svc = ChartService::new(FailingEngine);
        let err = svc.chart(&req()).unwrap_err();
        assert_eq!(err.to_string(), "chart engine failed");
        assert_eq!(std::error::Error::source(&err).unwrap().to_string(), "boom");
        let day = Date::from_calendar_date(2026, Month::November, 25).unwrap();
        assert!(matches!(svc.daily(&req(), day), Err(ChartError::Engine(_))));
    }
}
