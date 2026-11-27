//! Sample use case behind `GET /v1/info`; replace it with the service's first real one.

use crate::domain::info::{InfoError, ServiceInfo};

/// The use cases the HTTP adapter calls (a trait, so tests can inject fakes).
pub trait InfoUseCases: Send + Sync {
    fn info(&self) -> ServiceInfo;
}

/// Framework-free application service.
#[derive(Debug, Clone)]
pub struct InfoService {
    info: ServiceInfo,
}

impl InfoService {
    /// Fails on a blank name, so a misconfigured service stops at startup.
    pub fn new(service_name: &str) -> Result<Self, InfoError> {
        Ok(Self {
            info: ServiceInfo::new(service_name)?,
        })
    }
}

impl InfoUseCases for InfoService {
    fn info(&self) -> ServiceInfo {
        self.info.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn info_names_the_service() {
        let service = InfoService::new("svc").unwrap();
        assert_eq!(service.info().name(), "svc");
    }

    #[test]
    fn a_blank_name_is_rejected() {
        assert_eq!(InfoService::new("").unwrap_err(), InfoError::BlankName);
    }
}
