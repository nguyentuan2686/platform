//! HTTP API: `POST /astro/chart`, `POST /astro/transits`, the platform health endpoints and problem+json
//! `not-found` fallbacks (unknown path, wrong method).
//!
//! The request DTO keeps each field as raw JSON and denies unknown fields; the fields are
//! validated by hand so that every bad input names its field in a 400 problem. Chart:
//! `birth`, `zone`, `lat`, `lon`, `timeKnown`, `houseSystem`. Transits: the same fields of
//! the `natal` object as `natal.birth`, `natal.zone`, `natal.lat`, `natal.lon`,
//! `natal.timeKnown`, `natal.houseSystem`, plus `date` and `viewerZone`. Both use `body` for
//! malformed JSON, a non-object body, an unknown field or a missing or malformed `natal`.
//! The response DTOs live in `domain::chart` (serde, camelCase).
//! Neither the body nor the birth data is ever logged.

use axum::extract::{FromRequest, Request, State};
use axum::routing::post;
use axum::{Json, Router};
use platform_core::http::{health_router, http_metrics_layer, trace_layer, ApiError, FieldError};
use serde::Deserialize;
use serde_json::Value;
use time::{Date, Month, PrimitiveDateTime, Time};

use crate::app::AppState;
use crate::application::birth::{Birth, BirthError};
use crate::application::service::{ChartError, ChartRequest, TransitRequest};
use crate::domain::chart::{Chart, Transits};
use crate::domain::houses::HouseSystem;

/// Router with `/astro/chart`, `/health/live`, `/health/ready` and `not-found` problem
/// fallbacks for unknown paths and wrong methods, traced and metered per request.
pub fn router(state: AppState) -> Router {
    let readiness = state.readiness.clone();
    Router::new()
        .route("/astro/chart", post(chart))
        .route("/astro/transits", post(transits))
        .with_state(state)
        .merge(health_router(readiness))
        .fallback(not_found)
        .method_not_allowed_fallback(method_not_allowed)
        .layer(http_metrics_layer())
        .layer(trace_layer())
}

async fn not_found(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: req.uri().path().to_owned(),
    }
}

/// A known path with the wrong method. platform-core's `ApiError` has no 405 variant, so
/// this is the closest one, a `not-found` problem naming the method and path.
async fn method_not_allowed(req: Request) -> ApiError {
    ApiError::NotFound {
        resource: "route",
        id: format!("{} {}", req.method(), req.uri().path()),
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

/// Fields of a `POST /astro/chart` body. Every value stays raw JSON so each field is
/// validated by hand and named in errors; unknown fields are rejected on `body`.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct ChartFields {
    birth: Option<Value>,
    zone: Option<Value>,
    lat: Option<Value>,
    lon: Option<Value>,
    time_known: Option<Value>,
    house_system: Option<Value>,
}

/// Reads the body's fields; a non-object body or an unknown field is a 400 on `body`.
fn fields(body: Value) -> Result<ChartFields, ApiError> {
    if !body.is_object() {
        return Err(field_error("body", "must be a JSON object"));
    }
    // Every field is an optional raw value, so only an unknown field can fail here.
    serde_json::from_value(body).map_err(|_| field_error("body", "has an unknown field"))
}

/// Validates the fields into a request; every problem found is reported, each on its field.
fn parse(f: &ChartFields) -> Result<ChartRequest, ApiError> {
    let mut errors = Vec::new();
    let req = parse_into(f, &mut errors);
    match req {
        Some(req) if errors.is_empty() => Ok(req),
        _ => Err(ApiError::Validation(errors)),
    }
}

/// The shared chart-field validation (also the `natal` object of a transit request); the
/// problems are pushed on `errors`, named by the bare field.
fn parse_into(f: &ChartFields, errors: &mut Vec<FieldError>) -> Option<ChartRequest> {
    let local = required_str(f.birth.as_ref(), "birth", errors).and_then(|s| {
        parse_birth(s).or_else(|| {
            errors.push(fe("birth", "must be YYYY-MM-DDTHH:MM without an offset"));
            None
        })
    });
    let zone = required_str(f.zone.as_ref(), "zone", errors);
    let lat = required_number(f.lat.as_ref(), "lat", errors);
    let lon = required_number(f.lon.as_ref(), "lon", errors);
    let time_known = match f.time_known.as_ref() {
        Some(Value::Bool(b)) => Some(*b),
        None | Some(Value::Null) => {
            errors.push(fe("timeKnown", "is required"));
            None
        }
        Some(_) => {
            errors.push(fe("timeKnown", "must be a boolean"));
            None
        }
    };
    let house_system = match optional_str(f.house_system.as_ref(), "houseSystem", errors) {
        None | Some("placidus") => Some(HouseSystem::Placidus),
        Some("whole-sign") => Some(HouseSystem::WholeSign),
        Some("equal") => Some(HouseSystem::Equal),
        Some(_) => {
            errors.push(fe("houseSystem", "must be placidus, whole-sign or equal"));
            None
        }
    };
    match (local, zone, lat, lon, time_known, house_system) {
        (Some(local), Some(zone), Some(lat), Some(lon), Some(time_known), Some(house_system)) => {
            Some(ChartRequest {
                birth: Birth {
                    local,
                    zone: zone.to_owned(),
                    lat,
                    lon,
                    time_known,
                },
                house_system,
            })
        }
        _ => None,
    }
}

/// Fields of a `POST /astro/transits` body: the raw `natal` object (the chart fields), the
/// local `date` and the viewer's IANA zone.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct TransitFields {
    natal: Option<Value>,
    date: Option<Value>,
    viewer_zone: Option<Value>,
}

/// Validates a transit request. The natal chart fields are checked as for `/astro/chart`, their
/// errors named `natal.<field>`; a missing or malformed `natal` object is a `body` error.
fn parse_transits(body: Value) -> Result<TransitRequest, ApiError> {
    if !body.is_object() {
        return Err(field_error("body", "must be a JSON object"));
    }
    let f: TransitFields =
        serde_json::from_value(body).map_err(|_| field_error("body", "has an unknown field"))?;
    let mut errors = Vec::new();
    let natal = match f.natal {
        None | Some(Value::Null) => {
            errors.push(fe("body", "natal is required"));
            None
        }
        Some(v) if !v.is_object() => {
            errors.push(fe("body", "natal must be a JSON object"));
            None
        }
        Some(v) => {
            if let Ok(nf) = fields(v) {
                let mut natal_errors = Vec::new();
                let req = parse_into(&nf, &mut natal_errors);
                errors.extend(natal_errors.into_iter().map(|e| FieldError {
                    field: format!("natal.{}", e.field),
                    message: e.message,
                }));
                req
            } else {
                errors.push(fe("body", "natal has an unknown field"));
                None
            }
        }
    };
    let date = required_str(f.date.as_ref(), "date", &mut errors).and_then(|s| {
        parse_date(s).or_else(|| {
            errors.push(fe("date", "must be a valid date YYYY-MM-DD"));
            None
        })
    });
    let viewer_zone = required_str(f.viewer_zone.as_ref(), "viewerZone", &mut errors);
    match (natal, date, viewer_zone) {
        (Some(natal), Some(date), Some(zone)) if errors.is_empty() => Ok(TransitRequest {
            natal,
            date,
            viewer_zone: zone.to_owned(),
        }),
        _ => Err(ApiError::Validation(errors)),
    }
}

/// A required string field; records an error when missing or not a string.
fn required_str<'a>(
    v: Option<&'a Value>,
    name: &str,
    errors: &mut Vec<FieldError>,
) -> Option<&'a str> {
    match v {
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
    v: Option<&'a Value>,
    name: &str,
    errors: &mut Vec<FieldError>,
) -> Option<&'a str> {
    match v {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s),
        Some(_) => {
            errors.push(fe(name, "must be a string"));
            None
        }
    }
}

/// A required finite number field.
fn required_number(v: Option<&Value>, name: &str, errors: &mut Vec<FieldError>) -> Option<f64> {
    match v {
        None | Some(Value::Null) => {
            errors.push(fe(name, "is required"));
            None
        }
        Some(Value::Number(n)) => n.as_f64().filter(|x| x.is_finite()).or_else(|| {
            errors.push(fe(name, "must be a finite number"));
            None
        }),
        Some(_) => {
            errors.push(fe(name, "must be a number"));
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

/// `YYYY-MM-DD`; an impossible date such as 1990-02-30 is `None`.
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

/// `YYYY-MM-DDTHH:MM`, no seconds and no offset.
fn parse_birth(s: &str) -> Option<PrimitiveDateTime> {
    let b = s.as_bytes();
    if b.len() != 16 || b[10] != b'T' || b[13] != b':' {
        return None;
    }
    let hour = u8::try_from(digits(s, 11, 2)?).ok()?;
    let minute = u8::try_from(digits(s, 14, 2)?).ok()?;
    let time = Time::from_hms(hour, minute, 0).ok()?;
    Some(PrimitiveDateTime::new(parse_date(s.get(..10)?)?, time))
}

/// A chart failure as a problem: a bad birth names its field, an ephemeris failure is a 500
/// (`ApiError::Internal` logs it once with `error_type` and the cause chain, never the request).
fn chart_failure(e: ChartError, prefix: &str) -> ApiError {
    let natal = |field: &str, message: &str| field_error(&format!("{prefix}{field}"), message);
    match e {
        ChartError::Birth(b) => match b {
            BirthError::UnknownZone => natal("zone", "unknown IANA time zone"),
            BirthError::Nonexistent => natal("birth", "does not exist in the zone (DST gap)"),
            BirthError::OutOfRange => natal("birth", "must fall in the years 1900-2100"),
            BirthError::BadLatitude => natal("lat", "must be within [-90, 90]"),
            BirthError::BadLongitude => natal("lon", "must be within [-180, 180]"),
        },
        ChartError::DateOutOfRange => field_error("date", "must fall in the years 1900-2100"),
        ChartError::DateNonexistent => field_error(
            "date",
            "12:00 of this date does not exist in the zone viewerZone (DST gap or skipped day)",
        ),
        ChartError::ViewerZone => field_error("viewerZone", "unknown IANA time zone"),
        ChartError::Ephemeris(cause) => ApiError::Internal(cause.context("ephemeris failed")),
    }
}

// ---------------------------------------------------------------- handlers

async fn chart(State(state): State<AppState>, Body(body): Body) -> Result<Json<Chart>, ApiError> {
    let req = parse(&fields(body)?)?;
    let chart = state
        .service
        .chart(&req)
        .map_err(|e| chart_failure(e, ""))?;
    Ok(Json(chart))
}

async fn transits(
    State(state): State<AppState>,
    Body(body): Body,
) -> Result<Json<Transits>, ApiError> {
    let req = parse_transits(body)?;
    let transits = state
        .service
        .transits(&req)
        .map_err(|e| chart_failure(e, "natal."))?;
    Ok(Json(transits))
}
