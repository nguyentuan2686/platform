# kind e2e smoke-flow: orders topic never created, notify-svc idle for minutes

- Date: 2026-11-26
- Tags: infra, design

## Symptom
PR #30 run 45811274502, job `k8s-e2e / kind-e2e`: `smoke-flow` placed an order (201) but failed with
`no notification for order ... within 15s`. Diagnostics: order-svc logged
`{orders.order-placed.v1=UNKNOWN_TOPIC_OR_PARTITION}` and `Outbox publish failed ... will retry` every second;
notify-svc logged `Subscribed topic not available: orders.order-placed.v1` once at startup. Redpanda created only
`payments.payment-succeeded.v1` and its `.DLT` (declared by payment-svc and identity-svc via `KafkaAdmin`).

## Root cause
Nothing declared `orders.order-placed.v1`. In compose the Redpanda image defaults allow auto-create on the first
produce, so the gap never showed. The k8s Redpanda is bootstrapped from `.bootstrap.yaml`, which leaves
`auto_create_topics_enabled` at its default (off), so the outbox relay retried forever. A second gap: notify-svc
(librdkafka) only re-checks a subscribed topic that does not exist yet on `topic.metadata.refresh.interval.ms`,
5 min by default, so even once created the topic would have been picked up minutes later.

## Fix
- order-svc declares its topic like payment-svc does (`adapter/config/KafkaTopicsConfiguration`, `NewTopic`
  created by `KafkaAdmin` at startup).
- platform-core's consumer sets `topic.metadata.refresh.interval.ms=10000`, so a topic created after the
  subscribe (fresh cluster, producer starts later) is consumed within 10 s.

## How to catch earlier
Never rely on broker auto-create: every topic is declared at startup by its producer (and by its consumers when
they are Java/Spring). Compose hid the gap because its broker defaults differ from the k8s one; the kind e2e is the
first environment with prod-like broker settings.

## Escalation
rule: the second service that needed an explicit topic declaration (payment-svc's `KafkaTopicsConfiguration`
was added for the same reason). CLAUDE.md Logging/observability contract now says topics are declared, never
auto-created.
