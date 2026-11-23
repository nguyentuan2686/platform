# 2. Platform foundation

Date: 2026-11-23

## Status
Accepted (see `docs/superpowers/specs/2026-11-23-platform-foundation-design.md`)

## Context
A two-member team (owner + Claude) needs a standard, low-cost platform so new business problems only require adding a service. Constraints: zero cost initially, ARM64 and x86 targets, weak dev machine (2 cores / 12GB), no Oracle Cloud account, no domain yet.

## Decision
- Microservices from day one, polyglot (Java/Spring Boot and Rust), in a single monorepo with path-filtered CI.
- Runtime: k3s (Kubernetes) with Argo CD GitOps; manifests via Kustomize. Until a host exists, a throwaway kind cluster in CI acts as staging.
- Messaging: Redpanda (Kafka API). Services publish events with the transactional outbox pattern; consumers are idempotent on `event_id`.
- Observability: OpenTelemetry everywhere, self-hosted LGTM stack (Loki, Grafana, Tempo, Prometheus). The OTLP endpoint is configuration, so moving to Grafana Cloud needs no code change. Local dev uses `grafana/otel-lgtm`.
- Admin network: Tailscale (Kubernetes API, Argo CD, Grafana and SSH only inside the tailnet).
- Public traffic: Cloudflare quick tunnel until a domain is bought, then a named tunnel with `APP_DOMAIN`. Smoke tests use the in-tailnet ingress, not the public URL.
- CI/CD: GitHub Actions on a public repo; Claude squash-merges PRs when CI is green; multi-arch images to GHCR.
- Secrets: Sealed Secrets; CI secrets in GitHub Actions secrets.

## Consequences
Standard Kubernetes keeps cloud migration cheap, at the cost of ~2GB RAM overhead and more operational surface. Heavy verification happens in CI because the dev machine cannot host the cluster. The production host is decided before phase 5 (cheap VPS with >= 8GB, or a home machine behind a tunnel); bootstrap scripts must run on any Ubuntu 24.04, x86 or ARM.
