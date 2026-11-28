# order-svc verify failed pulling the Redpanda test image

- Date: 2026-11-28
- Tags: build | infra

## Symptom
PR #52 run 46118120310: `order-svc / verify` failed; every Testcontainers IT in platform-starter messaging errored
(`KafkaTopicsHealthIndicatorIT`, `ConsumerMetricsIT`) after "Retrying pull for image:
docker.redpanda.com/redpandadata/redpanda:v26.1.1". The PR did not touch Java code.

## Root cause
External registry flake (docker.redpanda.com pull timed out on the runner).

## Fix
`gh run rerun --failed`, no code change.

## How to catch earlier
Nothing to change yet. If it recurs, pull the image in a retried step before `mvn verify` (or mirror it to GHCR).

## Escalation
none (first occurrence)
