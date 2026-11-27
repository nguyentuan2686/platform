//! Configuration from environment variables (12-factor), parsed once at startup.

pub const DEFAULT_PORT: u16 = __PORT__;
pub const DEFAULT_SERVICE_NAME: &str = "__SERVICE__";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// `PORT` (default `DEFAULT_PORT`).
    pub port: u16,
    /// `OTEL_SERVICE_NAME` (default `DEFAULT_SERVICE_NAME`).
    pub service_name: String,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        Self::from_lookup(|k| std::env::var(k).ok())
    }

    /// Fails fast on an invalid `PORT`; the message names the variable, never its value.
    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        let var = |k: &str| get(k).filter(|v| !v.trim().is_empty());
        let port = match var("PORT") {
            None => DEFAULT_PORT,
            Some(p) => p
                .trim()
                .parse()
                .map_err(|_| anyhow::anyhow!("PORT must be a number between 0 and 65535"))?,
        };
        Ok(Self {
            port,
            service_name: var("OTEL_SERVICE_NAME")
                .map_or_else(|| DEFAULT_SERVICE_NAME.to_owned(), |s| s.trim().to_owned()),
        })
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use super::*;

    fn load(vars: &[(&str, &str)]) -> anyhow::Result<Config> {
        let map: HashMap<String, String> = vars
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect();
        Config::from_lookup(|k| map.get(k).cloned())
    }

    #[test]
    fn defaults() {
        let c = load(&[]).unwrap();
        assert_eq!(
            (c.port, c.service_name.as_str()),
            (DEFAULT_PORT, DEFAULT_SERVICE_NAME)
        );
    }

    #[test]
    fn overrides_and_blank_means_default() {
        let c = load(&[("PORT", " 9000 "), ("OTEL_SERVICE_NAME", "n")]).unwrap();
        assert_eq!((c.port, c.service_name.as_str()), (9000, "n"));
        assert_eq!(load(&[("PORT", "  ")]).unwrap().port, DEFAULT_PORT);
    }

    #[test]
    fn invalid_port_is_rejected_without_echo() {
        let err = load(&[("PORT", "70000x")]).unwrap_err().to_string();
        assert!(err.contains("PORT") && !err.contains("70000x"), "{err}");
    }
}
