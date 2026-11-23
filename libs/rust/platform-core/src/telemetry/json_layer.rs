//! One-JSON-object-per-line log layer, field-compatible with the Java `PlatformLogFormatter`:
//! `timestamp, level, service, trace_id, span_id, message`, optional `error` and `ctx`.

use std::fmt;
use std::io::Write;
use std::sync::OnceLock;

use opentelemetry::trace::TraceContextExt;
use serde_json::{json, Map, Value};
use time::format_description::well_known::Rfc3339;
use time::OffsetDateTime;
use tracing::dispatcher::WeakDispatch;
use tracing::field::{Field, Visit};
use tracing::{Dispatch, Event, Subscriber};
use tracing_opentelemetry::get_otel_context;
use tracing_subscriber::fmt::MakeWriter;
use tracing_subscriber::layer::Context;
use tracing_subscriber::registry::LookupSpan;
use tracing_subscriber::Layer;

/// Layer that writes each event as a single JSON line to the given writer.
pub struct JsonLayer<W> {
    service: String,
    make_writer: W,
    // `Span::current()` is unusable inside a subscriber callback (dispatcher re-entrancy guard),
    // so keep a weak handle to our own dispatcher to look up the event span's OTel context.
    dispatch: OnceLock<WeakDispatch>,
}

impl<W> JsonLayer<W> {
    pub fn new(service: impl Into<String>, make_writer: W) -> Self {
        Self {
            service: service.into(),
            make_writer,
            dispatch: OnceLock::new(),
        }
    }

    /// Trace and span ids (hex) of the event's enclosing span; empty strings if none/invalid.
    fn event_ids<S>(&self, event: &Event<'_>, ctx: &Context<'_, S>) -> (String, String)
    where
        S: Subscriber + for<'a> LookupSpan<'a>,
    {
        let otel_ctx = ctx
            .event_span(event)
            .map(|span| span.id())
            .zip(self.dispatch.get().and_then(WeakDispatch::upgrade))
            .and_then(|(id, dispatch)| get_otel_context(&id, &dispatch));
        otel_ctx.map_or_else(Default::default, |cx| ids_of(&cx))
    }
}

fn ids_of(otel_ctx: &opentelemetry::Context) -> (String, String) {
    let span = otel_ctx.span();
    let sc = span.span_context();
    if sc.is_valid() {
        (sc.trace_id().to_string(), sc.span_id().to_string())
    } else {
        (String::new(), String::new())
    }
}

/// `error.type` used when the concrete error type is unknown (`%e` / `?e` fields).
const DEFAULT_ERROR_TYPE: &str = "error";

/// Best-effort concrete type name of a `dyn Error`. Rust cannot name the concrete type behind a
/// trait object, so use the leading identifier of its derived `Debug` output (`MyErr { .. }`,
/// `ParseIntError { .. }`); fall back to [`DEFAULT_ERROR_TYPE`].
fn error_type_name(err: &(dyn std::error::Error + 'static)) -> String {
    let debug = format!("{err:?}");
    let ident: String = debug
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_')
        .collect();
    if ident.starts_with(|c: char| c.is_ascii_uppercase()) {
        ident
    } else {
        DEFAULT_ERROR_TYPE.to_owned()
    }
}

#[derive(Default)]
struct FieldVisitor {
    message: String,
    /// `(type, message)` of the `error` field.
    error: Option<(String, String)>,
    ctx: Map<String, Value>,
}

impl FieldVisitor {
    fn put(&mut self, field: &Field, value: Value) {
        self.ctx.insert(field.name().to_owned(), value);
    }
}

impl Visit for FieldVisitor {
    fn record_str(&mut self, field: &Field, value: &str) {
        match field.name() {
            "message" => value.clone_into(&mut self.message),
            "error" => self.error = Some((DEFAULT_ERROR_TYPE.to_owned(), value.to_owned())),
            _ => self.put(field, Value::String(value.to_owned())),
        }
    }

    fn record_i64(&mut self, field: &Field, value: i64) {
        self.put(field, json!(value));
    }

    fn record_u64(&mut self, field: &Field, value: u64) {
        self.put(field, json!(value));
    }

    fn record_bool(&mut self, field: &Field, value: bool) {
        self.put(field, json!(value));
    }

    fn record_f64(&mut self, field: &Field, value: f64) {
        self.put(field, json!(value));
    }

    fn record_error(&mut self, field: &Field, value: &(dyn std::error::Error + 'static)) {
        if field.name() == "error" {
            self.error = Some((error_type_name(value), value.to_string()));
        } else {
            self.put(field, Value::String(value.to_string()));
        }
    }

    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        let text = format!("{value:?}");
        match field.name() {
            "message" => self.message = text,
            "error" => self.error = Some((DEFAULT_ERROR_TYPE.to_owned(), text)),
            _ => self.put(field, Value::String(text)),
        }
    }
}

impl<S, W> Layer<S> for JsonLayer<W>
where
    S: Subscriber + for<'a> LookupSpan<'a>,
    W: for<'a> MakeWriter<'a> + 'static,
{
    fn on_register_dispatch(&self, subscriber: &Dispatch) {
        let _ = self.dispatch.set(subscriber.downgrade());
    }

    fn on_event(&self, event: &Event<'_>, ctx: Context<'_, S>) {
        let mut visitor = FieldVisitor::default();
        event.record(&mut visitor);
        let (trace_id, span_id) = self.event_ids(event, &ctx);

        let mut obj = Map::new();
        obj.insert(
            "timestamp".into(),
            Value::String(
                OffsetDateTime::now_utc()
                    .format(&Rfc3339)
                    .unwrap_or_default(),
            ),
        );
        obj.insert(
            "level".into(),
            Value::String(event.metadata().level().to_string()),
        );
        obj.insert("service".into(), Value::String(self.service.clone()));
        obj.insert("trace_id".into(), Value::String(trace_id));
        obj.insert("span_id".into(), Value::String(span_id));
        obj.insert("message".into(), Value::String(visitor.message));
        if let Some((kind, message)) = visitor.error {
            obj.insert("error".into(), json!({ "type": kind, "message": message }));
        }
        if !visitor.ctx.is_empty() {
            obj.insert("ctx".into(), Value::Object(visitor.ctx));
        }

        let mut line = Value::Object(obj).to_string();
        line.push('\n');
        // Logging must never fail the caller: ignore write errors.
        let _ = self.make_writer.make_writer().write_all(line.as_bytes());
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use opentelemetry::trace::{TraceContextExt, TracerProvider as _};
    use opentelemetry_sdk::trace::InMemorySpanExporter;
    use opentelemetry_sdk::trace::SdkTracerProvider;
    use tracing_opentelemetry::OpenTelemetrySpanExt;
    use tracing_subscriber::layer::SubscriberExt;

    use super::*;

    #[derive(Clone, Default)]
    struct Buf(Arc<Mutex<Vec<u8>>>);

    impl Write for Buf {
        fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(data);
            Ok(data.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl MakeWriter<'_> for Buf {
        type Writer = Buf;
        fn make_writer(&self) -> Buf {
            self.clone()
        }
    }

    impl Buf {
        fn text(&self) -> String {
            String::from_utf8(self.0.lock().unwrap().clone()).unwrap()
        }
    }

    fn capture(f: impl FnOnce()) -> String {
        let buf = Buf::default();
        let subscriber =
            tracing_subscriber::registry().with(JsonLayer::new("notify-svc", buf.clone()));
        tracing::subscriber::with_default(subscriber, f);
        buf.text()
    }

    fn parse(line: &str) -> Value {
        assert!(line.ends_with('\n'), "line must end with newline: {line:?}");
        assert_eq!(line.trim_end().lines().count(), 1, "exactly one line");
        serde_json::from_str(line.trim_end()).unwrap()
    }

    #[test]
    fn json_line_has_mandatory_fields() {
        let v = parse(&capture(|| tracing::info!("hello")));
        assert_eq!(v["level"], "INFO");
        assert_eq!(v["service"], "notify-svc");
        assert_eq!(v["message"], "hello");
        let ts = v["timestamp"].as_str().unwrap();
        assert!(ts.ends_with('Z'), "UTC: {ts}");
        OffsetDateTime::parse(ts, &Rfc3339).unwrap();
    }

    #[test]
    fn no_trace_context_gives_empty_ids() {
        let v = parse(&capture(|| tracing::info!("hello")));
        assert_eq!(v["trace_id"], "");
        assert_eq!(v["span_id"], "");
    }

    #[test]
    fn inside_otel_span_ids_match_span_context() {
        let exporter = InMemorySpanExporter::default();
        let provider = SdkTracerProvider::builder()
            .with_simple_exporter(exporter)
            .build();
        let tracer = provider.tracer("test");
        let buf = Buf::default();
        let subscriber = tracing_subscriber::registry()
            .with(tracing_opentelemetry::layer().with_tracer(tracer))
            .with(JsonLayer::new("notify-svc", buf.clone()));
        let expected = tracing::subscriber::with_default(subscriber, || {
            let span = tracing::info_span!("work");
            let _g = span.enter();
            tracing::info!("inside");
            let cx = span.context();
            let sc = cx.span().span_context().clone();
            (sc.trace_id().to_string(), sc.span_id().to_string())
        });
        let v = parse(&buf.text());
        assert_eq!(v["trace_id"], expected.0.as_str());
        assert_eq!(v["span_id"], expected.1.as_str());
        assert_eq!(expected.0.len(), 32);
        assert_eq!(expected.1.len(), 16);
    }

    #[test]
    fn event_fields_go_under_ctx() {
        let v = parse(&capture(|| tracing::info!(order_id = "42", count = 3, "x")));
        assert_eq!(v["ctx"]["order_id"], "42");
        assert_eq!(v["ctx"]["count"], 3);
        assert_eq!(v["message"], "x");
    }

    #[test]
    fn error_field_maps_to_error_object() {
        let e = std::io::Error::other("disk gone");
        let v = parse(&capture(|| tracing::error!(error = %e, "boom")));
        assert_eq!(v["error"]["message"], "disk gone");
        assert_eq!(v["error"]["type"], "error");
        assert!(v.get("ctx").is_none());
    }

    #[derive(Debug)]
    struct DiskGone;

    impl fmt::Display for DiskGone {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            f.write_str("disk gone")
        }
    }

    impl std::error::Error for DiskGone {}

    #[test]
    fn error_value_field_carries_type_name() {
        let e = DiskGone;
        let err: &(dyn std::error::Error + 'static) = &e;
        let v = parse(&capture(|| tracing::error!(error = err, "boom")));
        assert_eq!(v["error"]["type"], "DiskGone");
        assert_eq!(v["error"]["message"], "disk gone");
        assert!(v.get("ctx").is_none());
    }

    #[test]
    fn nested_and_explicit_parent_spans_pick_right_ids() {
        let provider = SdkTracerProvider::builder()
            .with_simple_exporter(InMemorySpanExporter::default())
            .build();
        let buf = Buf::default();
        let subscriber = tracing_subscriber::registry()
            .with(tracing_opentelemetry::layer().with_tracer(provider.tracer("test")))
            .with(JsonLayer::new("notify-svc", buf.clone()));
        let (a_id, b_id) = tracing::subscriber::with_default(subscriber, || {
            let a = tracing::info_span!("a");
            let _ga = a.enter();
            let b = tracing::info_span!("b");
            let _gb = b.enter();
            tracing::info!("in b");
            tracing::info!(parent: &a, "explicit a");
            let ids = |s: &tracing::Span| {
                let cx = s.context();
                let sc = cx.span().span_context().clone();
                (sc.trace_id().to_string(), sc.span_id().to_string())
            };
            (ids(&a), ids(&b))
        });
        assert_eq!(a_id.0, b_id.0, "same trace");
        assert_ne!(a_id.1, b_id.1);
        let text = buf.text();
        let lines: Vec<Value> = text
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        assert_eq!(lines[0]["span_id"], b_id.1.as_str());
        assert_eq!(lines[1]["span_id"], a_id.1.as_str());
        assert_eq!(lines[1]["trace_id"], a_id.0.as_str());
    }

    #[test]
    fn message_with_quote_backslash_newline_round_trips() {
        let msg = "a \"q\" b \\ c\nd";
        let v = parse(&capture(|| tracing::info!("{}", msg)));
        assert_eq!(v["message"], msg);
    }
}
