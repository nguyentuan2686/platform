-- Top-ups from PaymentSucceeded (revenue of the daily summary), one row per order however often the event arrives.
CREATE TABLE payments (
    order_id     uuid        PRIMARY KEY,
    product_code text        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor >= 0),
    currency     text        NOT NULL,
    paid_at      timestamptz NOT NULL
);

CREATE INDEX payments_paid_at_idx ON payments (paid_at);

-- Telegram messages to deliver (top-ups, daily summaries). `key` makes each one unique: payment:<order_id>,
-- summary:<YYYY-MM-DD>. The sender retries with backoff and expires what is a day old.
CREATE TABLE telegram_outbox (
    id              bigserial   PRIMARY KEY,
    key             text        NOT NULL UNIQUE,
    text            text        NOT NULL,
    created_at      timestamptz NOT NULL,
    status          text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'expired')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    sent_at         timestamptz
);

CREATE INDEX telegram_outbox_due_idx ON telegram_outbox (next_attempt_at) WHERE status = 'pending';
