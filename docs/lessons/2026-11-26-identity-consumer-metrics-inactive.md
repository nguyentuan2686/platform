# identity-svc exported no consumer RED metric: optional OpenTelemetry API missing

- Date: 2026-11-26
- Tags: design, infra

## Symptom
PR #30 run 45813026975, job `k8s-e2e / kind-e2e`: every smoke script passed and six of seven observability
checks passed, but `FAIL red-consumer`: Prometheus `count by (job) (messaging_process_duration_seconds_count{outcome="processed"})`
returned only `job="notify-svc"`, although identity-svc had applied the PaymentSucceeded event (credits 6).

## Root cause
`MessagingMetricsAutoConfiguration` (platform-starter) is `@ConditionalOnClass(GlobalOpenTelemetry.class)` and
the starter declares `opentelemetry-api` as `optional`. order-svc and payment-svc declare the API themselves;
identity-svc did not. The OpenTelemetry agent's classes are not on the application classpath, so the condition
was false and no consumer metrics interceptor was registered. `ConsumerMetricsIT` ran in the starter, which has
the API on its test classpath, so it could not see the gap.

## Fix
identity-svc declares `io.opentelemetry:opentelemetry-api` (version from the parent), like order-svc and payment-svc.

## How to catch earlier
An optional dependency that switches a feature on must be checked in every consuming service, not only in the
library's own tests. The kind e2e check `red-consumer` now covers every Java consumer; a new Java consumer must be
added to that check.

## Escalation
none (first occurrence)
