-- One chart profile per guest or account. The birth data is only stored encrypted (AES-256-GCM);
-- key_id names the key version for rotation. The birth lock lives on account.birth_locked_at, so
-- it survives deleting and recreating the profile.
CREATE TABLE chart_profile (
    id         uuid PRIMARY KEY,
    guest_id   uuid NULL UNIQUE REFERENCES guest (id),
    account_id uuid NULL UNIQUE REFERENCES account (id),
    label      text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 40),
    birth_enc  bytea NOT NULL,
    key_id     smallint NOT NULL,
    calendar   text NOT NULL CHECK (calendar IN ('VN', 'CN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((guest_id IS NULL) <> (account_id IS NULL))
);
