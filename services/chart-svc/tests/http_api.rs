//! HTTP adapter tests over the in-process router (`tower::ServiceExt::oneshot`).

use std::path::PathBuf;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use chart_svc::app::{router, AppState};
use chart_svc::application::birth::NormalizedBirth;
use chart_svc::application::ports::ZiweiEngine;
use chart_svc::domain::chart::{Chart, Flow, Gender};
use http_body_util::BodyExt as _;
use serde_json::{json, Value};
use tower::ServiceExt as _;

async fn get(uri: &str) -> (StatusCode, String, Value) {
    let res = router(AppState::new())
        .oneshot(Request::get(uri).body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = res.status();
    let ct = res
        .headers()
        .get("content-type")
        .map_or_default(|v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    (status, ct, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn live_is_up() {
    let (status, _, body) = get("/health/live").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, serde_json::json!({"status": "UP"}));
}

#[tokio::test]
async fn ready_is_up() {
    let (status, _, body) = get("/health/ready").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "UP");
}

#[tokio::test]
async fn unknown_route_is_a_not_found_problem() {
    let (status, ct, body) = get("/nope").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(body["type"], "urn:problem:not-found");
    assert_eq!(body["status"], 404);
    assert!(body["trace_id"].is_string(), "{body}");
}

#[tokio::test]
async fn wrong_method_on_a_route_is_a_problem() {
    // platform-core's ApiError has no method-not-allowed variant; the closest is not-found.
    let (status, ct, body) = get("/tuvi/chart").await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert!(ct.starts_with("application/problem+json"), "{ct}");
    assert_eq!(body["type"], "urn:problem:not-found");
    assert_eq!(body["detail"], "route GET /tuvi/chart not found");
    assert!(body["trace_id"].is_string(), "{body}");
}

// ---------------------------------------------------------------- POST /tuvi/*

struct Reply {
    status: StatusCode,
    content_type: String,
    bytes: Vec<u8>,
}

impl Reply {
    fn json(&self) -> Value {
        serde_json::from_slice(&self.bytes).unwrap()
    }
}

async fn send(state: AppState, uri: &str, content_type: &str, body: Vec<u8>) -> Reply {
    let res = router(state)
        .oneshot(
            Request::post(uri)
                .header("content-type", content_type)
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let content_type = res
        .headers()
        .get("content-type")
        .map_or_default(|v| v.to_str().unwrap().to_owned());
    let bytes = res.into_body().collect().await.unwrap().to_bytes().to_vec();
    Reply {
        status,
        content_type,
        bytes,
    }
}

async fn post_json(uri: &str, body: &Value) -> Reply {
    send(
        AppState::new(),
        uri,
        "application/json",
        serde_json::to_vec(body).unwrap(),
    )
    .await
}

fn base() -> Value {
    json!({"birth": "1994-03-12T08:30", "zone": "Asia/Ho_Chi_Minh", "gender": "male", "calendar": "VN"})
}

fn with(mut v: Value, key: &str, value: Value) -> Value {
    v[key] = value;
    v
}

fn without(mut v: Value, key: &str) -> Value {
    v.as_object_mut().unwrap().remove(key);
    v
}

async fn chart_of(body: &Value) -> Value {
    let r = post_json("/tuvi/chart", body).await;
    assert_eq!(
        r.status,
        StatusCode::OK,
        "{}",
        String::from_utf8_lossy(&r.bytes)
    );
    r.json()
}

#[tokio::test]
async fn chart_is_deterministic_and_matches_the_committed_snapshot() {
    let a = post_json("/tuvi/chart", &base()).await;
    let b = post_json("/tuvi/chart", &base()).await;
    assert_eq!(a.status, StatusCode::OK);
    assert!(
        a.content_type.starts_with("application/json"),
        "{}",
        a.content_type
    );
    assert_eq!(a.bytes, b.bytes, "two calls must be byte-identical");

    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/snap-1994-03-12.json");
    let committed = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read snapshot {}: {e}", path.display()));
    let committed: Value = serde_json::from_str(&committed).unwrap();
    assert_eq!(
        a.json(),
        committed,
        "chart output drifted from tests/fixtures/snap-1994-03-12.json; if the change is \
         intended, regenerate the snapshot by hand and review the diff"
    );
}

#[tokio::test]
async fn chart_has_the_contract_shape() {
    let c = chart_of(&base()).await;
    assert_eq!(
        c["school"],
        "vn+vn-calendar;hour=local-standard;ty23=next-day;leap=split15"
    );
    assert_eq!(c["calendar"], "VN");
    assert_eq!(c["hourBranch"], "Chen");
    let palaces = c["palaces"].as_array().unwrap();
    assert_eq!(palaces.len(), 12);
    let branches: Vec<_> = palaces
        .iter()
        .map(|p| p["branch"].as_str().unwrap())
        .collect();
    assert_eq!(
        branches,
        ["Yin", "Mao", "Chen", "Si", "Wu", "Wei", "Shen", "You", "Xu", "Hai", "Zi", "Chou"]
    );
    assert_eq!(palaces.iter().filter(|p| p["isBody"] == true).count(), 1);
    assert_eq!(
        palaces.iter().find(|p| p["isBody"] == true).unwrap()["key"],
        c["thanPalace"]
    );
    assert!(palaces.iter().all(|p| p["decade"]["from"].is_u64()));
    assert!(c["yearCanChi"]["nameVi"].as_str().unwrap().contains(' '));
}

#[tokio::test]
async fn zone_and_calendar_default_and_cn_has_its_own_school() {
    let defaulted = chart_of(&json!({"birth": "1994-03-12T08:30", "gender": "male"})).await;
    assert_eq!(defaulted, chart_of(&base()).await);
    let cn = chart_of(&with(base(), "calendar", json!("CN"))).await;
    assert_eq!(
        cn["school"],
        "iztro-default;hour=local-standard;ty23=next-day"
    );
    assert_eq!(cn["calendar"], "CN");
    // Seconds are accepted.
    let secs = chart_of(&with(base(), "birth", json!("1994-03-12T08:30:45"))).await;
    assert_eq!(secs, defaulted);
}

#[tokio::test]
async fn births_in_the_zi_hour_roll_to_the_next_day() {
    let c = chart_of(&with(base(), "birth", json!("1994-03-12T23:30"))).await;
    assert_eq!(c["hourBranch"], "Zi");
    // 1994-03-12 is lunar 2/1, so the rolled-over 1994-03-13 is 2/2.
    assert_eq!(c["lunar"]["month"], 2);
    assert_eq!(c["lunar"]["day"], 2);
}

#[tokio::test]
async fn south_vietnam_1970_is_read_at_utc_plus_7_so_2330_is_hour_hai_same_day() {
    // 23:30 at the then UTC+8 is 22:30 at UTC+7.
    let late = chart_of(&json!({"birth": "1970-06-01T23:30", "gender": "female"})).await;
    assert_eq!(late["hourBranch"], "Hai");
    // 00:10 on 06-02 at UTC+8 is 23:10 on 06-01 at UTC+7: next solar day, hour Zi.
    let next = chart_of(&json!({"birth": "1970-06-02T00:10", "gender": "female"})).await;
    assert_eq!(next["hourBranch"], "Zi");
    assert_eq!(
        next["lunar"]["day"].as_u64().unwrap(),
        late["lunar"]["day"].as_u64().unwrap() + 1
    );
}

#[tokio::test]
async fn hour_is_local_standard_time_and_the_calendar_never_changes_it() {
    let ny = |birth: &str, cal: &str| json!({"birth": birth, "zone": "America/New_York", "gender": "male", "calendar": cal});
    for cal in ["VN", "CN"] {
        // January: no daylight saving, local noon is hour Ngọ on the same day.
        let c = chart_of(&ny("2000-01-01T12:00", cal)).await;
        assert_eq!(c["hourBranch"], "Wu", "{cal}");
        // July: 12:30 EDT is 11:30 standard time, still Ngọ.
        let c = chart_of(&ny("2000-07-01T12:30", cal)).await;
        assert_eq!(c["hourBranch"], "Wu", "{cal}");
    }
    let vn = chart_of(&base()).await;
    let cn = chart_of(&with(base(), "calendar", json!("CN"))).await;
    assert_eq!(vn["hourBranch"], "Chen");
    assert_eq!(cn["hourBranch"], "Chen");
}

#[tokio::test]
async fn tet_1985_differs_between_vn_and_cn() {
    let at = |cal: &str| json!({"birth": "1985-02-20T12:00", "gender": "male", "calendar": cal});
    let vn = chart_of(&at("VN")).await;
    let cn = chart_of(&at("CN")).await;
    // Tet 1985 is 1985-01-21 in VN (UTC+7 new moon) and 1985-02-20 in CN.
    assert_ne!(vn["lunar"], cn["lunar"]);
    assert_eq!(
        cn["lunar"],
        json!({"year": 1985, "month": 1, "day": 1, "leap": false})
    );
    assert_ne!(vn["school"], cn["school"]);
}

#[tokio::test]
async fn leap_month_birth_reports_leap_flag() {
    let c = chart_of(&json!({"birth": "2023-04-01T08:30", "gender": "female"})).await;
    assert_eq!(c["lunar"]["month"], 2);
    assert_eq!(c["lunar"]["leap"], true);
    assert_eq!(c["lunar"]["year"], 2023);
}

#[tokio::test]
async fn invalid_inputs_are_400_validation_errors_naming_the_field() {
    let ny = with(base(), "zone", json!("America/New_York"));
    let cases: Vec<(&str, Value, &str)> = vec![
        (
            "/tuvi/chart",
            with(base(), "birth", json!("1994-02-30T08:30")),
            "birth",
        ),
        (
            "/tuvi/chart",
            with(base(), "birth", json!("1899-12-31T12:00")),
            "birth",
        ),
        (
            "/tuvi/chart",
            with(base(), "birth", json!("2101-01-01T12:00")),
            "birth",
        ),
        (
            "/tuvi/chart",
            with(base(), "birth", json!("2100-12-31T23:30")),
            "birth",
        ),
        (
            "/tuvi/chart",
            with(base(), "birth", json!("1994-03-12T08:30+07:00")),
            "birth",
        ),
        (
            "/tuvi/chart",
            with(base(), "birth", json!("12/03/1994")),
            "birth",
        ),
        ("/tuvi/chart", with(base(), "birth", json!(1994)), "birth"),
        ("/tuvi/chart", without(base(), "birth"), "birth"),
        (
            "/tuvi/chart",
            with(ny, "birth", json!("2021-03-14T02:30")),
            "birth",
        ),
    ];
    assert_all_rejected(cases).await;
}

#[tokio::test]
async fn invalid_zone_gender_calendar_date_and_body_are_400_naming_the_field() {
    let cases: Vec<(&str, Value, &str)> = vec![
        (
            "/tuvi/chart",
            with(base(), "timezone", json!("America/New_York")),
            "body",
        ),
        (
            "/tuvi/chart",
            with(base(), "date", json!("2026-11-25")),
            "body",
        ),
        (
            "/tuvi/daily",
            with(
                with(base(), "date", json!("2026-11-25")),
                "timezone",
                json!("UTC"),
            ),
            "body",
        ),
        (
            "/tuvi/chart",
            with(base(), "zone", json!("Mars/Base")),
            "zone",
        ),
        ("/tuvi/chart", with(base(), "zone", json!(7)), "zone"),
        (
            "/tuvi/chart",
            with(base(), "gender", json!("other")),
            "gender",
        ),
        (
            "/tuvi/chart",
            with(base(), "gender", json!("Male")),
            "gender",
        ),
        ("/tuvi/chart", without(base(), "gender"), "gender"),
        (
            "/tuvi/chart",
            with(base(), "calendar", json!("JP")),
            "calendar",
        ),
        (
            "/tuvi/chart",
            with(base(), "calendar", json!("vn")),
            "calendar",
        ),
        (
            "/tuvi/daily",
            with(base(), "date", json!("2026-11-25x")),
            "date",
        ),
        (
            "/tuvi/daily",
            with(base(), "date", json!("1899-12-31")),
            "date",
        ),
        (
            "/tuvi/daily",
            with(base(), "date", json!("2101-01-01")),
            "date",
        ),
        (
            "/tuvi/daily",
            with(base(), "date", json!("2026-02-30")),
            "date",
        ),
        ("/tuvi/daily", base(), "date"),
        (
            "/tuvi/daily",
            with(without(base(), "gender"), "date", json!("2026-11-25")),
            "gender",
        ),
        ("/tuvi/chart", json!([1, 2]), "body"),
        ("/tuvi/chart", json!("text"), "body"),
        ("/tuvi/chart", Value::Null, "body"),
    ];
    assert_all_rejected(cases).await;
}

async fn assert_all_rejected(cases: Vec<(&str, Value, &str)>) {
    for (uri, body, field) in cases {
        let r = post_json(uri, &body).await;
        assert_eq!(r.status, StatusCode::BAD_REQUEST, "{uri} {body}");
        assert!(
            r.content_type.starts_with("application/problem+json"),
            "{}",
            r.content_type
        );
        let p = r.json();
        assert_eq!(p["type"], "urn:problem:validation-error", "{body}");
        assert_eq!(p["errors"][0]["field"], field, "{uri} {body}: {p}");
        assert!(p["trace_id"].is_string());
    }
}

#[tokio::test]
async fn malformed_json_and_wrong_content_type_are_400_on_body() {
    for (ct, bytes) in [
        ("application/json", b"{not json".to_vec()),
        ("application/json", Vec::new()),
        ("text/plain", serde_json::to_vec(&base()).unwrap()),
    ] {
        let r = send(AppState::new(), "/tuvi/chart", ct, bytes).await;
        assert_eq!(r.status, StatusCode::BAD_REQUEST);
        let p = r.json();
        assert_eq!(p["type"], "urn:problem:validation-error");
        assert_eq!(p["errors"][0]["field"], "body");
    }
}

#[tokio::test]
async fn daily_returns_the_flow_layers() {
    let body = with(base(), "date", json!("2026-11-25"));
    let r = post_json("/tuvi/daily", &body).await;
    assert_eq!(r.status, StatusCode::OK);
    let d = r.json();
    assert_eq!(d["date"], "2026-11-25");
    assert!(d["lunar"]["month"].is_u64());
    assert!(d["dayCanChi"]["nameVi"].is_string());
    for layer in ["yearly", "monthly", "daily"] {
        let l = &d[layer];
        assert!(l["palaceKey"].is_string() && l["branch"].is_string() && l["stem"].is_string());
        assert_eq!(l["mutagens"].as_array().unwrap().len(), 4);
        assert_eq!(l["mutagens"][0]["mutagen"], "Loc");
    }
    // 2026 is a Bing-Wu year.
    assert_eq!(d["yearly"]["stem"], "Bing");
    assert_eq!(d["yearly"]["branch"], "Wu");
    let again = post_json("/tuvi/daily", &body).await;
    assert_eq!(r.bytes, again.bytes);
}

#[tokio::test]
async fn daily_accepts_the_range_edges() {
    for date in ["1900-01-01", "2100-12-31"] {
        let r = post_json("/tuvi/daily", &with(base(), "date", json!(date))).await;
        assert_eq!(
            r.status,
            StatusCode::OK,
            "{date}: {}",
            String::from_utf8_lossy(&r.bytes)
        );
    }
}

struct BrokenEngine;

impl ZiweiEngine for BrokenEngine {
    fn chart(&self, _: &NormalizedBirth, _: Gender) -> anyhow::Result<Chart> {
        Err(anyhow::anyhow!("secret engine detail"))
    }

    fn flow(&self, _: &NormalizedBirth, _: Gender, _: time::Date) -> anyhow::Result<Flow> {
        Err(anyhow::anyhow!("secret engine detail"))
    }
}

#[tokio::test]
async fn engine_failure_is_a_500_that_leaks_nothing() {
    for (uri, body) in [
        ("/tuvi/chart", base()),
        ("/tuvi/daily", with(base(), "date", json!("2026-11-25"))),
    ] {
        let r = send(
            AppState::with_engine(BrokenEngine),
            uri,
            "application/json",
            serde_json::to_vec(&body).unwrap(),
        )
        .await;
        assert_eq!(r.status, StatusCode::INTERNAL_SERVER_ERROR);
        let p = r.json();
        assert_eq!(p["type"], "urn:problem:internal-error");
        assert_eq!(p["detail"], "Unexpected error");
        assert!(!String::from_utf8_lossy(&r.bytes).contains("secret"));
    }
}

#[tokio::test]
async fn chart_reports_tuan_triet_adjectives_rings_and_small_limit_ages() {
    // 1994 is Giáp Tuất: Tuần covers Thân-Dậu, Triệt (stem Giáp) also Thân-Dậu.
    let c = chart_of(&base()).await;
    assert_eq!(c["tuan"], serde_json::json!(["Shen", "You"]));
    assert_eq!(c["triet"], serde_json::json!(["Shen", "You"]));
    let palaces = c["palaces"].as_array().unwrap();
    for flag in ["tuan", "triet"] {
        let on: Vec<_> = palaces
            .iter()
            .filter(|p| p[flag] == true)
            .map(|p| p["branch"].as_str().unwrap())
            .collect();
        assert_eq!(on, ["Shen", "You"], "{flag}");
    }
    let mut ages: Vec<u64> = palaces
        .iter()
        .flat_map(|p| p["smallLimitAges"].as_array().unwrap())
        .map(|a| a.as_u64().unwrap())
        .collect();
    ages.sort_unstable();
    assert_eq!(
        ages,
        (1..=120).collect::<Vec<u64>>(),
        "every age on one palace"
    );
    for p in palaces {
        assert_eq!(p["smallLimitAges"].as_array().unwrap().len(), 10);
        for ring in ["changsheng", "boshi", "suiqian", "jiangqian"] {
            assert_ne!(p["rings"][ring]["key"].as_str().unwrap(), "");
            assert_ne!(p["rings"][ring]["nameVi"].as_str().unwrap(), "");
        }
    }
    let adjective: Vec<&str> = palaces
        .iter()
        .flat_map(|p| p["adjective"].as_array().unwrap())
        .map(|a| a["key"].as_str().unwrap())
        .collect();
    assert!(adjective.contains(&"HongLoan"));
    assert!(
        !adjective
            .iter()
            .any(|k| k.starts_with("Tuan") || k.starts_with("Triet")),
        "Tuần/Triệt-type stars are palace pairs, not adjective stars"
    );
}
