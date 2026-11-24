//! HTTP API: `POST /tuvi/chart`, `POST /tuvi/daily`, the platform health endpoints and a
//! problem+json 404 fallback.
//!
//! The JSON DTOs (camelCase) live only here; the domain stays serde-free. Request fields are
//! validated by hand so that every bad input names its field (`birth`, `zone`, `gender`,
//! `calendar`, `date`; `body` for malformed JSON or a non-object body) in a 400 problem.

use axum::extract::{FromRequest, Request, State};
use axum::routing::post;
use axum::{Json, Router};
use platform_core::http::{health_router, trace_layer, ApiError, FieldError};
use serde::Serialize;
use serde_json::{Map, Value};
use time::{Date, Month, PrimitiveDateTime, Time};
use tracing::error;

use crate::app::AppState;
use crate::application::birth::{normalize, BirthError};
use crate::application::service::{ChartError, ChartRequest};
use crate::domain::calendar::{CalendarKind, CanChi, LunarDate};
use crate::domain::chart::{
    Brightness, Chart, Flow, FlowLayer, Gender, Mutagen, Palace, PalaceKey, StarInPalace,
};

/// Zone used when the request has none.
const DEFAULT_ZONE: &str = "Asia/Ho_Chi_Minh";
/// Supported solar years for `date`.
const YEARS: std::ops::RangeInclusive<i32> = 1900..=2100;

/// Router with the two Tu Vi endpoints, `/health/live`, `/health/ready` and a `not-found`
/// problem fallback, traced per request.
pub fn router(state: AppState) -> Router {
    let readiness = state.readiness.clone();
    Router::new()
        .route("/tuvi/chart", post(chart))
        .route("/tuvi/daily", post(daily))
        .with_state(state)
        .merge(health_router(readiness))
        .fallback(not_found)
        .layer(trace_layer())
}

async fn not_found(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: req.uri().path().to_owned(),
    }
}

// ---------------------------------------------------------------- request parsing

/// A JSON body whose rejections (bad JSON, wrong content type) are 400 problems on `body`.
struct Body(Value);

impl<S: Send + Sync> FromRequest<S> for Body {
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        match Json::<Value>::from_request(req, state).await {
            Ok(Json(v)) => Ok(Self(v)),
            Err(_) => Err(field_error("body", "must be a valid JSON object")),
        }
    }
}

fn field_error(field: &str, message: &str) -> ApiError {
    ApiError::Validation(vec![fe(field, message)])
}

fn fe(field: &str, message: &str) -> FieldError {
    FieldError {
        field: field.to_owned(),
        message: message.to_owned(),
    }
}

/// The parts of a request that need no zone lookup.
struct Parsed {
    local: PrimitiveDateTime,
    zone: String,
    gender: Gender,
    calendar: CalendarKind,
    date: Option<Date>,
}

fn parse(body: &Value, want_date: bool) -> Result<Parsed, ApiError> {
    let Some(obj) = body.as_object() else {
        return Err(field_error("body", "must be a JSON object"));
    };
    let mut errors = Vec::new();
    let local = required_str(obj, "birth", &mut errors).and_then(|s| {
        parse_birth(s).or_else(|| {
            errors.push(fe(
                "birth",
                "must be YYYY-MM-DDTHH:MM[:SS] without an offset",
            ));
            None
        })
    });
    let zone = optional_str(obj, "zone", &mut errors).unwrap_or(DEFAULT_ZONE);
    let gender = required_str(obj, "gender", &mut errors).and_then(|s| match s {
        "male" => Some(Gender::Male),
        "female" => Some(Gender::Female),
        _ => {
            errors.push(fe("gender", "must be male or female"));
            None
        }
    });
    let calendar = match optional_str(obj, "calendar", &mut errors) {
        None | Some("VN") => Some(CalendarKind::Vn),
        Some("CN") => Some(CalendarKind::Cn),
        Some(_) => {
            errors.push(fe("calendar", "must be VN or CN"));
            None
        }
    };
    let date = if want_date {
        required_str(obj, "date", &mut errors).and_then(|s| {
            parse_date(s)
                .filter(|d| YEARS.contains(&d.year()))
                .or_else(|| {
                    errors.push(fe("date", "must be YYYY-MM-DD between 1900 and 2100"));
                    None
                })
        })
    } else {
        None
    };
    match (local, gender, calendar) {
        (Some(local), Some(gender), Some(calendar)) if errors.is_empty() => Ok(Parsed {
            local,
            zone: zone.to_owned(),
            gender,
            calendar,
            date,
        }),
        _ => Err(ApiError::Validation(errors)),
    }
}

/// A required string field; records an error when missing or not a string.
fn required_str<'a>(
    obj: &'a Map<String, Value>,
    name: &str,
    errors: &mut Vec<FieldError>,
) -> Option<&'a str> {
    match obj.get(name) {
        None | Some(Value::Null) => {
            errors.push(fe(name, "is required"));
            None
        }
        Some(Value::String(s)) => Some(s),
        Some(_) => {
            errors.push(fe(name, "must be a string"));
            None
        }
    }
}

/// An optional string field; `null` counts as absent.
fn optional_str<'a>(
    obj: &'a Map<String, Value>,
    name: &str,
    errors: &mut Vec<FieldError>,
) -> Option<&'a str> {
    match obj.get(name) {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s),
        Some(_) => {
            errors.push(fe(name, "must be a string"));
            None
        }
    }
}

/// `n` ASCII digits at `s[at..at + n]` as a number.
fn digits(s: &str, at: usize, n: usize) -> Option<u32> {
    let part = s.get(at..at + n)?;
    if part.bytes().all(|b| b.is_ascii_digit()) {
        part.parse().ok()
    } else {
        None
    }
}

/// `YYYY-MM-DD`.
fn parse_date(s: &str) -> Option<Date> {
    let b = s.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return None;
    }
    let year = i32::try_from(digits(s, 0, 4)?).ok()?;
    let month = Month::try_from(u8::try_from(digits(s, 5, 2)?).ok()?).ok()?;
    let day = u8::try_from(digits(s, 8, 2)?).ok()?;
    Date::from_calendar_date(year, month, day).ok()
}

/// `YYYY-MM-DDTHH:MM` with optional `:SS`; no offset.
fn parse_birth(s: &str) -> Option<PrimitiveDateTime> {
    let b = s.as_bytes();
    if !matches!(b.len(), 16 | 19) || b[10] != b'T' || b[13] != b':' {
        return None;
    }
    let second = if b.len() == 19 {
        if b[16] != b':' {
            return None;
        }
        u8::try_from(digits(s, 17, 2)?).ok()?
    } else {
        0
    };
    let hour = u8::try_from(digits(s, 11, 2)?).ok()?;
    let minute = u8::try_from(digits(s, 14, 2)?).ok()?;
    let time = Time::from_hms(hour, minute, second).ok()?;
    Some(PrimitiveDateTime::new(parse_date(s.get(..10)?)?, time))
}

fn chart_request(p: &Parsed) -> Result<ChartRequest, ApiError> {
    let birth = normalize(p.local, &p.zone, p.calendar).map_err(|e| match e {
        BirthError::UnknownZone => field_error("zone", "unknown IANA time zone"),
        BirthError::OutOfRange => field_error("birth", "must fall in the years 1900-2100"),
        BirthError::Nonexistent => field_error("birth", "does not exist in the zone (DST gap)"),
    })?;
    Ok(ChartRequest {
        birth,
        gender: p.gender,
    })
}

fn engine_failure(e: ChartError) -> ApiError {
    error!(error = %e, error_type = "ChartEngineError", "chart engine failed");
    let ChartError::Engine(cause) = e;
    ApiError::Internal(cause)
}

// ---------------------------------------------------------------- handlers

async fn chart(
    State(state): State<AppState>,
    Body(body): Body,
) -> Result<Json<ChartDto>, ApiError> {
    let parsed = parse(&body, false)?;
    let req = chart_request(&parsed)?;
    let chart = state.service.chart(&req).map_err(engine_failure)?;
    Ok(Json(ChartDto::new(&chart, parsed.calendar)))
}

async fn daily(
    State(state): State<AppState>,
    Body(body): Body,
) -> Result<Json<DailyDto>, ApiError> {
    let parsed = parse(&body, true)?;
    let req = chart_request(&parsed)?;
    let date = parsed
        .date
        .ok_or_else(|| field_error("date", "is required"))?;
    let chart = state.service.chart(&req).map_err(engine_failure)?;
    let flow = state.service.daily(&req, date).map_err(engine_failure)?;
    Ok(Json(DailyDto::new(&flow, &chart)?))
}

// ---------------------------------------------------------------- response DTOs

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LunarDto {
    year: i32,
    month: u8,
    day: u8,
    leap: bool,
}

impl From<LunarDate> for LunarDto {
    fn from(l: LunarDate) -> Self {
        Self {
            year: l.year,
            month: l.month,
            day: l.day,
            leap: l.leap,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CanChiDto {
    stem: &'static str,
    branch: &'static str,
    name_vi: String,
}

impl From<CanChi> for CanChiDto {
    fn from(c: CanChi) -> Self {
        Self {
            stem: c.stem.key(),
            branch: c.branch.key(),
            name_vi: format!("{} {}", c.stem.name_vi(), c.branch.name_vi()),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CucDto {
    element: &'static str,
    number: u8,
    name_vi: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DecadeDto {
    from: u8,
    to: u8,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StarDto {
    key: &'static str,
    name_vi: &'static str,
    brightness: Option<&'static str>,
    mutagen: Option<&'static str>,
}

impl From<&StarInPalace> for StarDto {
    fn from(s: &StarInPalace) -> Self {
        Self {
            key: s.key.key(),
            name_vi: s.key.name_vi(),
            brightness: s.brightness.map(Brightness::key),
            mutagen: s.mutagen.map(Mutagen::key),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PalaceDto {
    key: &'static str,
    name_vi: &'static str,
    branch: &'static str,
    stem: &'static str,
    is_body: bool,
    decade: DecadeDto,
    major: Vec<StarDto>,
    minor: Vec<StarDto>,
}

impl PalaceDto {
    fn new(p: &Palace, body: PalaceKey) -> Self {
        Self {
            key: p.key.key(),
            name_vi: p.key.name_vi(),
            branch: p.branch.key(),
            stem: p.stem.key(),
            is_body: p.key == body,
            decade: DecadeDto {
                from: p.decade.0,
                to: p.decade.1,
            },
            major: p.major.iter().map(StarDto::from).collect(),
            minor: p.minor.iter().map(StarDto::from).collect(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ChartDto {
    school: &'static str,
    calendar: &'static str,
    lunar: LunarDto,
    year_can_chi: CanChiDto,
    hour_branch: &'static str,
    cuc: CucDto,
    menh_chu: &'static str,
    than_chu: &'static str,
    than_palace: &'static str,
    palaces: Vec<PalaceDto>,
}

fn calendar_name(c: CalendarKind) -> &'static str {
    match c {
        CalendarKind::Vn => "VN",
        CalendarKind::Cn => "CN",
    }
}

impl ChartDto {
    fn new(c: &Chart, calendar: CalendarKind) -> Self {
        Self {
            school: c.school,
            calendar: calendar_name(calendar),
            lunar: c.lunar.into(),
            year_can_chi: c.year.into(),
            hour_branch: c.hour_branch.key(),
            cuc: CucDto {
                element: c.cuc.element.key(),
                number: c.cuc.number,
                name_vi: c.cuc.name_vi(),
            },
            menh_chu: c.menh_chu.key(),
            than_chu: c.than_chu.key(),
            than_palace: c.than_palace.key(),
            palaces: c
                .palaces
                .iter()
                .map(|p| PalaceDto::new(p, c.than_palace))
                .collect(),
        }
    }
}

#[derive(Serialize)]
struct MutagenDto {
    star: &'static str,
    mutagen: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LayerDto {
    palace_key: &'static str,
    branch: &'static str,
    stem: &'static str,
    mutagens: Vec<MutagenDto>,
}

impl LayerDto {
    /// `palace_key` is the natal palace that sits on the layer's branch; a chart without
    /// one (impossible for a valid chart) is an internal error, never an empty key.
    fn new(l: &FlowLayer, chart: &Chart) -> Result<Self, ApiError> {
        let palace_key = chart
            .palaces
            .iter()
            .find(|p| p.branch == l.palace_branch)
            .map(|p| p.key.key())
            .ok_or_else(|| {
                ApiError::Internal(anyhow::anyhow!(
                    "natal chart has no palace on flow branch {}",
                    l.palace_branch.key()
                ))
            })?;
        Ok(Self {
            palace_key,
            branch: l.palace_branch.key(),
            stem: l.stem.key(),
            mutagens: l
                .mutagens
                .iter()
                .map(|(s, m)| MutagenDto {
                    star: s.key(),
                    mutagen: m.key(),
                })
                .collect(),
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DailyDto {
    date: String,
    lunar: LunarDto,
    day_can_chi: CanChiDto,
    yearly: LayerDto,
    monthly: LayerDto,
    daily: LayerDto,
}

impl DailyDto {
    fn new(f: &Flow, chart: &Chart) -> Result<Self, ApiError> {
        Ok(Self {
            date: format!(
                "{:04}-{:02}-{:02}",
                f.date.year(),
                u8::from(f.date.month()),
                f.date.day()
            ),
            lunar: f.lunar.into(),
            day_can_chi: f.day.into(),
            yearly: LayerDto::new(&f.yearly, chart)?,
            monthly: LayerDto::new(&f.monthly, chart)?,
            daily: LayerDto::new(&f.daily, chart)?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn birth_format() {
        assert!(parse_birth("1994-03-12T08:30").is_some());
        assert!(parse_birth("1994-03-12T08:30:15").is_some());
        for bad in [
            "1994-03-12",
            "1994-03-12 08:30",
            "1994-03-12T08:30Z",
            "1994-03-12T08:30+07:00",
            "1994-02-30T08:30",
            "1994-03-12T24:00",
            "1994-03-12T08:60",
            "1994-03-12T08:30:6",
            "1994-03-12T08-30",
            "+994-03-12T08:30",
            "1994-03-12T08:30:ab",
            "é994-03-12T08:30",
        ] {
            assert!(parse_birth(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn date_format() {
        assert!(parse_date("2026-11-25").is_some());
        assert!(parse_date("2026-13-01").is_none());
        assert!(parse_date("2026/11/25").is_none());
        assert!(parse_date("2026-11-2x").is_none());
    }

    #[test]
    fn parse_collects_field_errors_and_defaults() {
        let ok = parse(
            &json!({"birth": "1994-03-12T08:30", "gender": "male"}),
            false,
        )
        .unwrap();
        assert_eq!(ok.zone, DEFAULT_ZONE);
        assert_eq!(ok.calendar, CalendarKind::Vn);
        let err = parse(&json!({"birth": 5, "gender": null, "zone": 1}), false);
        let Err(ApiError::Validation(fields)) = err else {
            panic!("expected validation error");
        };
        let names: Vec<_> = fields.iter().map(|f| f.field.as_str()).collect();
        assert_eq!(names, ["birth", "zone", "gender"]);
        assert!(matches!(
            parse(&json!([1]), false),
            Err(ApiError::Validation(f)) if f[0].field == "body"
        ));
    }

    fn chart_1994() -> Chart {
        use crate::adapters::engine::IztroAdapter;
        use crate::application::ports::ZiweiEngine as _;
        let day = Date::from_calendar_date(1994, Month::March, 12).unwrap();
        let birth = crate::application::birth::NormalizedBirth::from_parts(
            day,
            crate::domain::calendar::Branch::Chen,
            CalendarKind::Vn,
        );
        IztroAdapter.chart(&birth, Gender::Male).unwrap()
    }

    #[test]
    fn layer_names_the_natal_palace_on_its_branch() {
        let chart = chart_1994();
        let p = &chart.palaces[3];
        let layer = FlowLayer {
            palace_branch: p.branch,
            stem: p.stem,
            mutagens: [(crate::domain::chart::StarKey::TuVi, Mutagen::Loc); 4],
        };
        let dto = LayerDto::new(&layer, &chart).unwrap();
        assert_eq!(dto.palace_key, p.key.key());
        assert_eq!(dto.branch, p.branch.key());
    }

    #[test]
    fn layer_on_a_branch_without_a_palace_is_an_internal_error() {
        let mut chart = chart_1994();
        let missing = chart.palaces[0].branch;
        chart.palaces[0].branch = chart.palaces[1].branch;
        let layer = FlowLayer {
            palace_branch: missing,
            stem: chart.palaces[0].stem,
            mutagens: [(crate::domain::chart::StarKey::TuVi, Mutagen::Loc); 4],
        };
        assert!(matches!(
            LayerDto::new(&layer, &chart),
            Err(ApiError::Internal(_))
        ));
    }
}
