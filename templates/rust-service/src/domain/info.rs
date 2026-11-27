//! What the service says about itself. A value object: the name is never blank.

/// The service's identity, as served by `GET /v1/info`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceInfo {
    name: String,
}

/// Why a [`ServiceInfo`] could not be built.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum InfoError {
    #[error("service name must not be blank")]
    BlankName,
}

impl ServiceInfo {
    pub fn new(name: impl Into<String>) -> Result<Self, InfoError> {
        let name = name.into();
        if name.trim().is_empty() {
            return Err(InfoError::BlankName);
        }
        Ok(Self { name })
    }

    pub fn name(&self) -> &str {
        &self.name
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_name() {
        assert_eq!(ServiceInfo::new("svc").unwrap().name(), "svc");
    }

    #[test]
    fn rejects_a_blank_name() {
        assert_eq!(ServiceInfo::new("  "), Err(InfoError::BlankName));
    }
}
