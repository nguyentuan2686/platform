-- Platform-owned idempotency ledger for Kafka consumers. Lives in schema `platform` and is tracked
-- in `platform._sqlx_migrations`, separately from each service's own migrations.
CREATE TABLE IF NOT EXISTS platform.processed_event (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
