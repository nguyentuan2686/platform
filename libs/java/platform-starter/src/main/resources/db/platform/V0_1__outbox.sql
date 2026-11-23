-- Platform transactional outbox (dev.platform.starter.outbox). Services add
-- classpath:db/platform to spring.flyway.locations. Never edit once applied.
create table outbox_event (
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
create index outbox_event_unpublished_idx on outbox_event (occurred_at) where published_at is null;
