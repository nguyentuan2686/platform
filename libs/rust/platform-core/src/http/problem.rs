//! RFC 9457 problem details (`application/problem+json`) with the platform `trace_id` extension,
//! plus extractors whose rejections are problem responses instead of axum's plain text.

use axum::extract::rejection::{JsonRejection, PathRejection};
use axum::extract::{FromRequest, FromRequestParts, Path, Request};
use axum::http::request::Parts;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::de::DeserializeOwned;
use serde::Serialize;
use tracing::error;

use crate::telemetry::current_trace_id;

const PROBLEM_JSON: &str = "application/problem+json";

/// One invalid field in a validation problem.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FieldError {
    pub field: String,
    pub message: String,
}

/// Error type for HTTP handlers; renders as a problem document.
#[derive(Debug)]
pub enum ApiError {
    NotFound {
        resource: &'static str,
        id: String,
    },
    Validation(Vec<FieldError>),
    BadRequest(String),
    /// Logged at ERROR; the client only ever sees `"Unexpected error"`.
    Internal(anyhow::Error),
}

#[derive(Serialize)]
struct Problem {
    #[serde(rename = "type")]
    type_: String,
    title: &'static str,
    status: u16,
    detail: String,
    trace_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    errors: Option<Vec<FieldError>>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, slug, title, detail, errors) = match self {
            ApiError::NotFound { resource, id } => (
                StatusCode::NOT_FOUND,
                "not-found",
                "Not Found",
                format!("{resource} {id} not found"),
                None,
            ),
            ApiError::Validation(errors) => (
                StatusCode::BAD_REQUEST,
                "validation",
                "Bad Request",
                "Request validation failed".to_owned(),
                Some(errors),
            ),
            ApiError::BadRequest(detail) => (
                StatusCode::BAD_REQUEST,
                "bad-request",
                "Bad Request",
                detail,
                None,
            ),
            ApiError::Internal(e) => {
                error!(error = %format!("{e:#}"), error_type = "InternalError", "request failed");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal-error",
                    "Internal Server Error",
                    "Unexpected error".to_owned(),
                    None,
                )
            }
        };
        let problem = Problem {
            type_: format!("urn:problem:{slug}"),
            title,
            status: status.as_u16(),
            detail,
            trace_id: current_trace_id(),
            errors,
        };
        // Serializing this struct cannot fail (plain strings/ints).
        let body = serde_json::to_vec(&problem).unwrap_or_default();
        let mut res = (status, body).into_response();
        res.headers_mut()
            .insert(header::CONTENT_TYPE, HeaderValue::from_static(PROBLEM_JSON));
        res
    }
}

/// `Json<T>` whose rejections (malformed JSON, wrong shape, wrong content type) are 400 problems.
pub struct ApiJson<T>(pub T);

impl<S, T> FromRequest<S> for ApiJson<T>
where
    S: Send + Sync,
    T: DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        match axum::Json::<T>::from_request(req, state).await {
            Ok(axum::Json(v)) => Ok(Self(v)),
            Err(rej) => Err(json_rejection(&rej)),
        }
    }
}

fn json_rejection(rej: &JsonRejection) -> ApiError {
    // Fixed messages: never echo parser internals back to the client.
    let detail = match rej {
        JsonRejection::MissingJsonContentType(_) => {
            "Expected request with `Content-Type: application/json`"
        }
        JsonRejection::JsonDataError(_) => "Request body does not match the expected schema",
        JsonRejection::JsonSyntaxError(_) => "Request body is not valid JSON",
        _ => "Failed to read request body",
    };
    ApiError::BadRequest(detail.to_owned())
}

/// Query-string extractor; a bad value yields a 400 validation problem whose `errors[0].field`
/// is the offending parameter name.
pub struct ApiQuery<T>(pub T);

#[allow(clippy::unused_async_trait_impl)] // axum trait signature is async; no await needed here
impl<S, T> FromRequestParts<S> for ApiQuery<T>
where
    S: Send + Sync,
    T: DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let query = parts.uri.query().unwrap_or_default();
        let de = serde_urlencoded::Deserializer::new(form_urlencoded::parse(query.as_bytes()));
        match serde_path_to_error::deserialize::<_, T>(de) {
            Ok(v) => Ok(Self(v)),
            Err(e) => {
                let field = e.path().to_string();
                if field.is_empty() || field == "." {
                    Err(ApiError::BadRequest("Invalid query string".to_owned()))
                } else {
                    Err(ApiError::Validation(vec![FieldError {
                        field,
                        message: "invalid value".to_owned(),
                    }]))
                }
            }
        }
    }
}

/// Path-parameter extractor with problem-JSON rejection (400).
pub struct ApiPath<T>(pub T);

impl<S, T> FromRequestParts<S> for ApiPath<T>
where
    S: Send + Sync,
    T: DeserializeOwned + Send,
{
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        match Path::<T>::from_request_parts(parts, state).await {
            Ok(Path(v)) => Ok(Self(v)),
            Err(rej) => Err(path_rejection(&rej)),
        }
    }
}

fn path_rejection(_rej: &PathRejection) -> ApiError {
    ApiError::BadRequest("Invalid path parameter".to_owned())
}
