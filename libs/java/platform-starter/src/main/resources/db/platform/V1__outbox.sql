-- Platform transactional outbox (dev.platform.starter.outbox). Applied by the starter itself
-- (PlatformFlywayMigrator) from classpath:db/platform with its own history table
-- platform_schema_history, before the service's Flyway runs; services do not list db/platform.
-- Never edit once applied; add V2__..., V3__... here for later platform schema changes.
-- "if not exists": a schema migrated before the platform history existed may already have it.
create table if not exists outbox_event (
    event_id     uuid        primary key,
    event_type   text        not null,
    topic        text        not null,
    msg_key      text        not null,
    payload      jsonb       not null,
    traceparent  text,
    occurred_at  timestamptz not null,
    published_at timestamptz,
    attempts     int         not null default 0
);

-- The relay only ever scans unpublished rows, oldest first.
create index if not exists outbox_event_unpublished_idx
    on outbox_event (occurred_at) where published_at is null;
