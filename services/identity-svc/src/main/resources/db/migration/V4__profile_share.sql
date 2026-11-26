-- Public share link of a chart profile. The id is 16 random bytes (base64url, 22 chars); at most
-- one active (not revoked) link per profile. Deleting the profile deletes its links.
CREATE TABLE profile_share (
    id         text PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES chart_profile (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz NULL
);
CREATE UNIQUE INDEX profile_share_active ON profile_share (profile_id) WHERE revoked_at IS NULL;
