-- Passes (one row per paid pass; order_id is unique so a replayed payment never grants twice) and
-- the append-only credit ledger. A CREDIT balance is sum(delta) over source = 'CREDIT'; the
-- questions used on a pass are -sum(delta) over its pass_id. unique (reason, ref) makes every
-- grant, consume, refund and reversal idempotent on its own key (order id, reading id, account id).
CREATE TABLE pass (
    id                  uuid PRIMARY KEY,
    account_id          uuid NOT NULL REFERENCES account (id),
    kind                text NOT NULL CHECK (kind IN ('MONTH', 'YEAR')),
    order_id            uuid NOT NULL UNIQUE,
    starts_at           timestamptz NOT NULL,
    ends_at             timestamptz NOT NULL,
    questions_included  int NULL
);
CREATE INDEX pass_account_idx ON pass (account_id);

CREATE TABLE credit_ledger (
    id         bigserial PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id),
    delta      int NOT NULL,
    source     text NOT NULL CHECK (source IN ('CREDIT', 'PASS')),
    pass_id    uuid NULL REFERENCES pass (id),
    reason     text NOT NULL CHECK (reason IN ('signup_bonus', 'purchase', 'consume', 'refund', 'reversal')),
    ref        text NOT NULL,
    topic      text NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reason, ref)
);
CREATE INDEX credit_ledger_account_idx ON credit_ledger (account_id, id);

CREATE TABLE processed_event (
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
