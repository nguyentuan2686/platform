//! Outbound HTTP: the Telegram Bot API (messenger) and Prometheus + Grafana (the day's numbers).
//!
//! The bot token is part of the Telegram URL, so no error from here ever carries a URL: reqwest errors are stripped
//! with `without_url`, and status failures name the status code only.

use std::time::Duration;

use anyhow::Context as _;
use async_trait::async_trait;
use serde::Deserialize;
use serde_json::{json, Value};
use time::OffsetDateTime;

use crate::application::{DayMetricsPort, MessengerPort};
use crate::domain::{ErrorRate, Uses};

/// Per-request bound: a hung upstream must not stall the sender or the summary loop.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

/// One HTTP client for all outbound calls (TLS via rustls + ring; system roots of the image).
pub fn http_client() -> anyhow::Result<reqwest::Client> {
    // catch-ok: Err only means a provider is already installed for this process, which is what we need.
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .build()
        .map_err(reqwest::Error::without_url)
        .context("building the HTTP client")
}

/// Sends to one chat through `<api>/bot<token>/sendMessage` (plain text, no link previews).
pub struct TelegramMessenger {
    client: reqwest::Client,
    url: String,
    chat_id: String,
}

impl TelegramMessenger {
    pub fn new(client: reqwest::Client, api_url: &str, token: &str, chat_id: &str) -> Self {
        Self {
            client,
            url: format!("{}/bot{token}/sendMessage", api_url.trim_end_matches('/')),
            chat_id: chat_id.to_owned(),
        }
    }
}

impl std::fmt::Debug for TelegramMessenger {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TelegramMessenger")
            .field("url", &"<redacted>")
            .finish_non_exhaustive()
    }
}

#[async_trait]
impl MessengerPort for TelegramMessenger {
    async fn send(&self, text: &str) -> anyhow::Result<()> {
        let body = json!({"chat_id": self.chat_id, "text": text, "link_preview_options": {"is_disabled": true}});
        let res = self
            .client
            .post(&self.url)
            .json(&body)
            .send()
            .await
            .map_err(reqwest::Error::without_url)
            .context("telegram sendMessage")?;
        let status = res.status();
        if !status.is_success() {
            anyhow::bail!("telegram sendMessage answered {}", status.as_u16());
        }
        Ok(())
    }
}

/// Prometheus (uses, 5xx share) and Grafana (alert state history) inside the cluster.
pub struct ClusterDayMetrics {
    client: reqwest::Client,
    prometheus: String,
    grafana: String,
}

const USES_QUERY_ROUTES: &str = "/tuvi/readings/basic|/tuvi/qa/readings|/tuvi/number-check";

impl ClusterDayMetrics {
    pub fn new(client: reqwest::Client, prometheus_url: &str, grafana_url: &str) -> Self {
        Self {
            client,
            prometheus: prometheus_url.trim_end_matches('/').to_owned(),
            grafana: grafana_url.trim_end_matches('/').to_owned(),
        }
    }

    /// Instant query at `to`; each sample as (labels, value).
    async fn query(&self, promql: &str, to: OffsetDateTime) -> anyhow::Result<Vec<(Value, f64)>> {
        let res: PromResponse = self
            .client
            .get(format!("{}/api/v1/query", self.prometheus))
            .query(&[
                ("query", promql),
                ("time", &to.unix_timestamp().to_string()),
            ])
            .send()
            .await
            .map_err(reqwest::Error::without_url)
            .context("prometheus query")?
            .error_for_status()
            .map_err(reqwest::Error::without_url)
            .context("prometheus query")?
            .json()
            .await
            .map_err(reqwest::Error::without_url)
            .context("prometheus response")?;
        if res.status != "success" {
            anyhow::bail!("prometheus answered status {}", res.status);
        }
        res.data
            .result
            .into_iter()
            .map(|s| {
                let v = s
                    .value
                    .1
                    .parse::<f64>()
                    .context("prometheus sample value")?;
                Ok((s.metric, if v.is_finite() { v } else { 0.0 }))
            })
            .collect()
    }
}

#[derive(Deserialize)]
struct PromResponse {
    status: String,
    data: PromData,
}
#[derive(Deserialize)]
struct PromData {
    result: Vec<PromSample>,
}
#[derive(Deserialize)]
struct PromSample {
    metric: Value,
    value: (f64, String),
}

/// `[86400s]`: the window from `from` to `to`, at least one second.
fn window(from: OffsetDateTime, to: OffsetDateTime) -> String {
    format!("{}s", (to - from).whole_seconds().max(1))
}

/// Rounds a counter increase (Prometheus extrapolates, so it is fractional) to a whole count.
fn count(v: f64) -> u64 {
    // Truncation is the intent after rounding; negative or NaN cannot reach here (filtered in `query`).
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let n = v.max(0.0).round() as u64;
    n
}

#[async_trait]
impl DayMetricsPort for ClusterDayMetrics {
    async fn uses(&self, from: OffsetDateTime, to: OffsetDateTime) -> anyhow::Result<Uses> {
        let q = format!(
            "sum by (http_route) (increase(http_server_request_duration_seconds_count{{job=\"reading-tuvi\", \
             http_request_method=\"POST\", http_response_status_code=~\"2..\", http_route=~\"{USES_QUERY_ROUTES}\"}}[{}]))",
            window(from, to)
        );
        let mut u = Uses::default();
        for (labels, v) in self.query(&q, to).await? {
            match labels["http_route"].as_str() {
                Some("/tuvi/readings/basic") => u.basic_readings = count(v),
                Some("/tuvi/qa/readings") => u.qa = count(v),
                Some("/tuvi/number-check") => u.number_checks = count(v),
                _ => {}
            }
        }
        Ok(u)
    }

    async fn error_rates(
        &self,
        from: OffsetDateTime,
        to: OffsetDateTime,
    ) -> anyhow::Result<Vec<ErrorRate>> {
        let w = window(from, to);
        let base = "http_server_request_duration_seconds_count{http_route!~\"/health/.*\"";
        let q = format!(
            "100 * sum by (job) (increase({base}, http_response_status_code=~\"5..\"}}[{w}])) \
             / sum by (job) (increase({base}}}[{w}])) > 0"
        );
        Ok(self
            .query(&q, to)
            .await?
            .into_iter()
            .filter_map(|(labels, v)| {
                labels["job"].as_str().map(|job| ErrorRate {
                    service: job.to_owned(),
                    percent: v,
                })
            })
            .collect())
    }

    async fn alerts_fired(&self, from: OffsetDateTime, to: OffsetDateTime) -> anyhow::Result<u64> {
        let ms = |t: OffsetDateTime| (t.unix_timestamp_nanos() / 1_000_000).to_string();
        let items: Vec<Value> = self
            .client
            .get(format!("{}/api/annotations", self.grafana))
            .query(&[
                ("type", "alert"),
                ("from", &ms(from)),
                ("to", &ms(to)),
                ("limit", "5000"),
            ])
            .send()
            .await
            .map_err(reqwest::Error::without_url)
            .context("grafana annotations")?
            .error_for_status()
            .map_err(reqwest::Error::without_url)
            .context("grafana annotations")?
            .json()
            .await
            .map_err(reqwest::Error::without_url)
            .context("grafana annotations response")?;
        // A rule entering Alerting (newState "Alerting", or "Alerting (...)" with a reason) counts as one alert fired.
        Ok(items
            .iter()
            .filter(|a| {
                a["newState"]
                    .as_str()
                    .is_some_and(|s| s.starts_with("Alerting"))
            })
            .count() as u64)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use axum::extract::{Query, State};
    use axum::http::StatusCode;
    use axum::routing::{get, post};
    use axum::{Json, Router};
    use time::macros::datetime;

    use super::*;

    type Seen = Arc<Mutex<Vec<String>>>;

    /// Fake Telegram + Prometheus + Grafana on a random port; records what it was asked.
    async fn upstream(telegram_status: u16) -> (String, Seen) {
        let seen: Seen = Arc::default();
        let app = Router::new()
            .route(
                "/botTOKEN-SECRET/sendMessage",
                post(move |State(s): State<Seen>, Json(b): Json<Value>| async move {
                    s.lock().unwrap().push(b.to_string());
                    StatusCode::from_u16(telegram_status).unwrap()
                }),
            )
            .route(
                "/api/v1/query",
                get(|State(s): State<Seen>, Query(q): Query<Vec<(String, String)>>| async move {
                    let query = q.iter().find(|(k, _)| k == "query").map(|(_, v)| v.clone()).unwrap();
                    let time = q.iter().find(|(k, _)| k == "time").map(|(_, v)| v.clone()).unwrap();
                    s.lock().unwrap().push(format!("time={time} {query}"));
                    let result = if query.contains("by (http_route)") {
                        json!([
                            {"metric": {"http_route": "/tuvi/readings/basic"}, "value": [1, "11.6"]},
                            {"metric": {"http_route": "/tuvi/qa/readings"}, "value": [1, "4"]},
                            {"metric": {"http_route": "/tuvi/number-check"}, "value": [1, "0.2"]}
                        ])
                    } else {
                        json!([{"metric": {"job": "reading-tuvi"}, "value": [1, "2.5"]}])
                    };
                    Json(json!({"status": "success", "data": {"resultType": "vector", "result": result}}))
                }),
            )
            .route(
                "/api/annotations",
                get(|State(s): State<Seen>, Query(q): Query<Vec<(String, String)>>| async move {
                    s.lock().unwrap().push(format!("annotations {q:?}"));
                    Json(json!([
                        {"newState": "Alerting", "prevState": "Pending"},
                        {"newState": "Normal", "prevState": "Alerting"},
                        {"newState": "Alerting (Error)", "prevState": "Normal"}
                    ]))
                }),
            )
            .with_state(seen.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (url, seen)
    }

    #[tokio::test]
    async fn telegram_posts_plain_text_to_the_chat() {
        let (url, seen) = upstream(200).await;
        let m = TelegramMessenger::new(http_client().unwrap(), &url, "TOKEN-SECRET", "7076");
        m.send("💰 Nạp tiền").await.unwrap();
        let body: Value = serde_json::from_str(&seen.lock().unwrap()[0]).unwrap();
        assert_eq!(body["chat_id"], "7076");
        assert_eq!(body["text"], "💰 Nạp tiền");
        assert!(!format!("{m:?}").contains("TOKEN-SECRET"));
    }

    #[tokio::test]
    async fn telegram_errors_never_carry_the_token() {
        let (url, _) = upstream(502).await;
        let err = TelegramMessenger::new(http_client().unwrap(), &url, "TOKEN-SECRET", "1")
            .send("x")
            .await
            .unwrap_err();
        let msg = format!("{err:#}");
        assert!(
            msg.contains("502") && !msg.contains("TOKEN-SECRET"),
            "{msg}"
        );

        // connection refused: reqwest would print the URL (with the token) unless stripped
        let err = TelegramMessenger::new(
            http_client().unwrap(),
            "http://127.0.0.1:9",
            "TOKEN-SECRET",
            "1",
        )
        .send("x")
        .await
        .unwrap_err();
        let msg = format!("{err:#} {err:?}");
        assert!(!msg.contains("TOKEN-SECRET"), "{msg}");
    }

    #[tokio::test]
    async fn day_metrics_query_the_day_window_at_its_end() {
        let (url, seen) = upstream(200).await;
        let m = ClusterDayMetrics::new(http_client().unwrap(), &url, &url);
        let (from, to) = (
            datetime!(2026-11-27 17:00 UTC),
            datetime!(2026-11-28 17:00 UTC),
        );
        assert_eq!(
            m.uses(from, to).await.unwrap(),
            Uses {
                basic_readings: 12,
                qa: 4,
                number_checks: 0
            }
        );
        assert_eq!(
            m.error_rates(from, to).await.unwrap(),
            vec![ErrorRate {
                service: "reading-tuvi".into(),
                percent: 2.5
            }]
        );
        assert_eq!(m.alerts_fired(from, to).await.unwrap(), 2);
        let seen = seen.lock().unwrap();
        assert!(
            seen[0].starts_with(&format!("time={} ", to.unix_timestamp())),
            "{}",
            seen[0]
        );
        assert!(
            seen[0].contains("[86400s]") && seen[0].contains("http_request_method=\"POST\""),
            "{}",
            seen[0]
        );
        assert!(
            seen[1].contains("http_response_status_code=~\"5..\""),
            "{}",
            seen[1]
        );
        assert!(
            seen[2].contains("\"type\", \"alert\"") && seen[2].contains("1795798800000"),
            "{}",
            seen[2]
        );
    }

    #[tokio::test]
    async fn unreachable_metrics_are_errors() {
        let m = ClusterDayMetrics::new(
            http_client().unwrap(),
            "http://127.0.0.1:9",
            "http://127.0.0.1:9",
        );
        let (from, to) = (
            datetime!(2026-11-27 17:00 UTC),
            datetime!(2026-11-28 17:00 UTC),
        );
        assert!(m.uses(from, to).await.is_err());
        assert!(m.alerts_fired(from, to).await.is_err());
    }
}
