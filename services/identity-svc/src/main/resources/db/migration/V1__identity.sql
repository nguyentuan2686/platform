CREATE TABLE guest (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    merged_into uuid NULL
);

CREATE TABLE account (
    id              uuid PRIMARY KEY,
    email           text NOT NULL UNIQUE,
    google_sub      text UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    birth_locked_at timestamptz NULL
);

CREATE TABLE session (
    id           uuid PRIMARY KEY,
    token_sha256 bytea NOT NULL UNIQUE,
    guest_id     uuid NULL REFERENCES guest (id),
    account_id   uuid NULL REFERENCES account (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz NULL,
    CHECK ((guest_id IS NULL) <> (account_id IS NULL))
);

CREATE TABLE magic_link (
    id           uuid PRIMARY KEY,
    email        text NOT NULL,
    token_sha256 bytea NOT NULL UNIQUE,
    guest_id     uuid NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    used_at      timestamptz NULL
);
CREATE INDEX magic_link_email_created_idx ON magic_link (email, created_at);

CREATE TABLE oidc_login (
    state         text PRIMARY KEY,
    nonce         text NOT NULL,
    code_verifier text NOT NULL,
    redirect_uri  text NOT NULL,
    guest_id      uuid NULL,
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz NULL
);
