//! Configuration from environment variables (12-factor), parsed once at startup.

use std::fmt;

pub const DEFAULT_PORT: u16 = 8081;
pub const DEFAULT_GROUP_ID: &str = "notify-svc";
pub const DEFAULT_SERVICE_NAME: &str = "notify-svc";
pub const DEFAULT_PAYMENTS_GROUP_ID: &str = "notify-svc-payments";
pub const DEFAULT_TELEGRAM_API_URL: &str = "https://api.telegram.org";
pub const DEFAULT_PROMETHEUS_URL: &str = "http://lgtm.observability.svc.cluster.local:9090";
pub const DEFAULT_GRAFANA_URL: &str = "http://lgtm.observability.svc.cluster.local:3000";

/// The owner's Telegram bot and chat. The token is a secret: never logged (see `Debug`).
#[derive(Clone, PartialEq, Eq)]
pub struct TelegramConfig {
    pub bot_token: String,
    pub chat_id: String,
}

impl fmt::Debug for TelegramConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TelegramConfig")
            .field("bot_token", &"<redacted>")
            .field("chat_id", &self.chat_id)
            .finish()
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct Config {
    /// `DB_URL` (required). May contain a password: never logged (see `Debug`).
    pub db_url: String,
    /// `KAFKA_BOOTSTRAP_SERVERS` (required).
    pub kafka_bootstrap_servers: String,
    /// `PORT` (default 8081).
    pub port: u16,
    /// `KAFKA_GROUP_ID` (default `notify-svc`).
    pub kafka_group_id: String,
    /// `OTEL_SERVICE_NAME` (default `notify-svc`).
    pub service_name: String,
    /// `KAFKA_PAYMENTS_GROUP_ID` (default `notify-svc-payments`): group of the `PaymentSucceeded` consumer.
    pub payments_group_id: String,
    /// `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID`, both or neither; none: messages are queued but not sent.
    pub telegram: Option<TelegramConfig>,
    /// `TELEGRAM_API_URL` (default `https://api.telegram.org`).
    pub telegram_api_url: String,
    /// `PROMETHEUS_URL`, `GRAFANA_URL`: the daily summary's numbers (defaults: lgtm in the cluster).
    pub prometheus_url: String,
    pub grafana_url: String,
}

impl fmt::Debug for Config {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Config")
            .field("db_url", &"<redacted>")
            .field("kafka_bootstrap_servers", &self.kafka_bootstrap_servers)
            .field("port", &self.port)
            .field("kafka_group_id", &self.kafka_group_id)
            .field("service_name", &self.service_name)
            .field("payments_group_id", &self.payments_group_id)
            .field("telegram", &self.telegram.as_ref().map(|_| "<redacted>"))
            .field("telegram_api_url", &self.telegram_api_url)
            .field("prometheus_url", &self.prometheus_url)
            .field("grafana_url", &self.grafana_url)
            .finish()
    }
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        Self::from_lookup(|k| std::env::var(k).ok())
    }

    /// Fails fast on missing/empty required variables or an invalid `PORT`; error messages name
    /// variables, never their values.
    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        let var = |k: &str| get(k).filter(|v| !v.trim().is_empty());
        let missing: Vec<&str> = ["DB_URL", "KAFKA_BOOTSTRAP_SERVERS"]
            .into_iter()
            .filter(|k| var(k).is_none())
            .collect();
        if !missing.is_empty() {
            anyhow::bail!(
                "missing required environment variable(s): {}",
                missing.join(", ")
            );
        }
        let port = match var("PORT") {
            None => DEFAULT_PORT,
            Some(p) => p
                .trim()
                .parse()
                .map_err(|_| anyhow::anyhow!("PORT must be a number between 0 and 65535"))?,
        };
        let telegram = match (var("TELEGRAM_BOT_TOKEN"), var("TELEGRAM_CHAT_ID")) {
            (Some(bot_token), Some(chat_id)) => Some(TelegramConfig {
                bot_token: bot_token.trim().to_owned(),
                chat_id: chat_id.trim().to_owned(),
            }),
            (None, None) => None,
            _ => anyhow::bail!("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID must be set together"),
        };
        let or = |k: &str, d: &str| var(k).unwrap_or_else(|| d.to_owned());
        Ok(Self {
            db_url: var("DB_URL").unwrap_or_default(),
            kafka_bootstrap_servers: var("KAFKA_BOOTSTRAP_SERVERS").unwrap_or_default(),
            port,
            kafka_group_id: or("KAFKA_GROUP_ID", DEFAULT_GROUP_ID),
            service_name: or("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME),
            payments_group_id: or("KAFKA_PAYMENTS_GROUP_ID", DEFAULT_PAYMENTS_GROUP_ID),
            telegram,
            telegram_api_url: or("TELEGRAM_API_URL", DEFAULT_TELEGRAM_API_URL),
            prometheus_url: or("PROMETHEUS_URL", DEFAULT_PROMETHEUS_URL),
            grafana_url: or("GRAFANA_URL", DEFAULT_GRAFANA_URL),
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

    const REQUIRED: [(&str, &str); 2] = [
        ("DB_URL", "postgres://notify:s3cret@db/notify"),
        ("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
    ];

    #[test]
    fn defaults() {
        let c = load(&REQUIRED).unwrap();
        assert_eq!(c.port, 8081);
        assert_eq!(c.kafka_group_id, "notify-svc");
        assert_eq!(c.service_name, "notify-svc");
        assert_eq!(c.kafka_bootstrap_servers, "kafka:9092");
        assert_eq!(c.db_url, "postgres://notify:s3cret@db/notify");
    }

    #[test]
    fn overrides() {
        let mut vars = REQUIRED.to_vec();
        vars.extend([
            ("PORT", "9000"),
            ("KAFKA_GROUP_ID", "g"),
            ("OTEL_SERVICE_NAME", "n"),
        ]);
        let c = load(&vars).unwrap();
        assert_eq!(
            (c.port, c.kafka_group_id.as_str(), c.service_name.as_str()),
            (9000, "g", "n")
        );
    }

    #[test]
    fn missing_required_fails_fast_naming_variables() {
        let err = load(&[]).unwrap_err().to_string();
        assert!(
            err.contains("DB_URL") && err.contains("KAFKA_BOOTSTRAP_SERVERS"),
            "{err}"
        );
        let err = load(&[("DB_URL", "x"), ("KAFKA_BOOTSTRAP_SERVERS", " ")]).unwrap_err();
        assert!(err.to_string().contains("KAFKA_BOOTSTRAP_SERVERS"));
    }

    #[test]
    fn invalid_port_is_rejected_without_echo() {
        let mut vars = REQUIRED.to_vec();
        vars.push(("PORT", "70000x"));
        let err = load(&vars).unwrap_err().to_string();
        assert!(err.contains("PORT") && !err.contains("70000x"), "{err}");
    }

    #[test]
    fn debug_redacts_db_url() {
        let dbg = format!("{:?}", load(&REQUIRED).unwrap());
        assert!(!dbg.contains("s3cret"), "{dbg}");
        assert!(dbg.contains("kafka:9092"));
    }

    #[test]
    fn telegram_is_optional_but_needs_both_values() {
        let c = load(&REQUIRED).unwrap();
        assert_eq!(c.telegram, None);
        assert_eq!(c.payments_group_id, "notify-svc-payments");
        assert_eq!(
            c.prometheus_url,
            "http://lgtm.observability.svc.cluster.local:9090"
        );

        let mut vars = REQUIRED.to_vec();
        vars.extend([
            ("TELEGRAM_BOT_TOKEN", "123:tok-secret"),
            ("TELEGRAM_CHAT_ID", " 42 "),
        ]);
        let c = load(&vars).unwrap();
        let t = c.telegram.as_ref().unwrap();
        assert_eq!(
            (t.bot_token.as_str(), t.chat_id.as_str()),
            ("123:tok-secret", "42")
        );
        assert!(!format!("{c:?}").contains("tok-secret"), "{c:?}");

        let mut half = REQUIRED.to_vec();
        half.push(("TELEGRAM_BOT_TOKEN", "123:tok-secret"));
        let err = load(&half).unwrap_err().to_string();
        assert!(
            err.contains("TELEGRAM_CHAT_ID") && !err.contains("tok-secret"),
            "{err}"
        );
    }
}
