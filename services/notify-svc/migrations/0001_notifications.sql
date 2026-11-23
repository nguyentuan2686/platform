CREATE TABLE notifications (
    id          uuid        PRIMARY KEY,
    order_id    uuid        NOT NULL,
    customer_id text        NOT NULL,
    channel     text        NOT NULL,
    message     text        NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX notifications_created_at_idx ON notifications (created_at DESC);
