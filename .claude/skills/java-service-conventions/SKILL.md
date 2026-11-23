---
name: java-service-conventions
description: Use when creating or modifying a Java/Spring Boot service or library in this repo, or reviewing such a change; covers package layout, ArchUnit rules, test pyramid, naming, error mapping, logging.
---

# Java service conventions

Java 21, groupId/base package `dev.platform`. Full rules also in `CLAUDE.md`.

## Package layout (hexagonal)
```
dev.platform.<svc>.domain        # entities, value objects (records), domain services, ports' types. NO Spring/Jakarta/Jackson imports
dev.platform.<svc>.application   # use cases (*UseCase), port interfaces (*Port); orchestrates domain
dev.platform.<svc>.adapter.in    # REST controllers, Kafka consumers
dev.platform.<svc>.adapter.out   # JdbcClient repositories, outbox event adapters, HTTP clients (*Adapter)
```
Dependencies point inward only: adapter -> application -> domain.

## ArchUnit rules (one test class per service, must exist)
- `domain..` must not depend on `org.springframework..`, `jakarta..`, `tools.jackson..` (Jackson 3), `com.fasterxml..` (Jackson 2 / annotations)
- `domain..` and `application..` must not depend on `adapter..`
- No field injection: no `@Autowired` on fields; constructor injection only
- Classes in `adapter.in` must not access `adapter.out` directly (go via application)

## Naming
- `*UseCase` (application entry points), `*Port` (interfaces owned by application), `*Adapter` (implementations in adapter.out)
- Records for DTOs and value objects; request/response DTOs live in `adapter.in`

## Test pyramid (write test first, red -> green -> refactor)
1. Domain unit tests: plain JUnit, no Spring
2. Application tests: use cases with in-memory fakes of ports (no mocks of own code where a fake is simple)
3. Adapter slice tests: `@WebMvcTest` for controllers (import `PlatformWebAutoConfiguration` to get the Problem Details handler); persistence adapters against real Postgres (Testcontainers), never H2
4. Integration: `@SpringBootTest` + Testcontainers (Postgres, Kafka/Redpanda); few, happy-path plus key failures
- Gate: >= 80% line coverage on `domain` and `application` (JaCoCo, enforced by `mvn -q verify`)

## Persistence (JDBC, no JPA)
- `spring-boot-starter-jdbc` + Flyway + Postgres driver; repositories in `adapter.out.persistence` use `JdbcClient` with plain SQL and map rows to domain types by hand (e.g. `Order.restore(...)`); no JPA/Hibernate, no entity annotations anywhere.
- Writes run inside the use case's `@Transactional` boundary (the adapter just joins it), so the outbox row commits atomically with the aggregate.
- Test persistence against real Postgres via Testcontainers with the real Flyway migrations: the service `*IT` (`@SpringBootTest`) or a JDBC slice with `@AutoConfigureTestDatabase(replace = NONE)`; never H2.

## Outbox opt-in (`dev.platform.starter.outbox`)
- The starter declares these as optional; a service opts in by adding all of them itself: `spring-boot-starter-jdbc`, `spring-boot-starter-flyway` (+ `flyway-database-postgresql`), `spring-boot-starter-kafka`, `spring-boot-starter-jackson`, `io.opentelemetry:opentelemetry-api`.
- `OutboxAutoConfiguration` silently backs off if any of them is missing (or there is no `DataSource`/transaction manager/`KafkaTemplate`, or `platform.outbox.enabled=false`): no writer, no relay, no error. If `OutboxWriter` cannot be injected, check those dependencies first.
- Platform migrations run automatically: the starter applies `classpath:db/platform` (the `outbox_event` table) with its own Flyway history table `platform_schema_history`, before the service's Flyway. Do NOT add `classpath:db/platform` to `spring.flyway.locations`; service migrations stay in `db/migration` with their own `flyway_schema_history`.
- Write with `OutboxWriter.append(...)` inside the business transaction (it fails outside one); the relay publishes asynchronously, at-least-once, with the request's `traceparent`.
- Settings: `platform.outbox.{poll-interval, batch-size, send-timeout}`.

## Error mapping (RFC 9457 `application/problem+json`, always with `trace_id`)
| Cause | Status | type slug |
|---|---|---|
| Bean validation / malformed input | 400 | `validation-error` |
| Not found | 404 | `not-found` |
| Business rule violated / state conflict | 409 | `conflict` |
| Idempotency/optimistic-lock clash | 409 | `conflict` |
| Downstream unavailable | 503 | `dependency-unavailable` |
| Anything unexpected | 500 | `internal-error` (generic detail) |
Never expose stack traces, exception class names, SQL, or internal ids in `detail`.

## Logging
- JSON on stdout; mandatory fields `timestamp, level, service, trace_id, span_id, message`
- Log at boundaries (inbound request result, outbound call failure, event consumed/published), not inside domain logic
- Never log payloads or PII (names, emails, phones, addresses, tokens); log ids only
- No `System.out`/`printStackTrace`; use SLF4J with parameterized messages

## Other
- Database per service; schema changes only via Flyway migrations (`V<n>__desc.sql`), never edit applied ones
- Config via env vars; `/health/live` and `/health/ready` exposed
- Events: envelope `{event_id, event_type, occurred_at, data}`; consumers idempotent on `event_id`
- Format: `mvn spotless:apply` (google-java-format); verify: `mvn -q verify`

## Review checklist
- [ ] Domain free of framework imports; ArchUnit green
- [ ] Test written first; pyramid layers appropriate; coverage gate met
- [ ] Errors are Problem Details with `trace_id`, no internals leaked
- [ ] No PII/payloads in logs; no secrets in code or config
- [ ] Flyway migration added for schema changes
- [ ] Contract (`contracts/openapi`, `contracts/events`) updated if API/event changed
- [ ] `mvn -q verify` output read and green
