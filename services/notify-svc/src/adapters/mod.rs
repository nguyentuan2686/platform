//! Adapters: HTTP API, Postgres persistence, Kafka consumers, outbound HTTP (Telegram, Prometheus, Grafana) and
//! system clock/ids.
pub mod db;
pub mod http;
pub mod messaging;
pub mod outbound;
pub mod system;
