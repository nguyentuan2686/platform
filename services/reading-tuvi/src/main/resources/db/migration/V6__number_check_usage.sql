-- Free number check (xem số): one row per distinct number a subject checked on a Vietnam day.
-- Only keyed HMACs are stored, never the number or the IP; rows older than yesterday are purged.
CREATE TABLE number_check_usage (
  subject_kind text        NOT NULL CHECK (subject_kind IN ('guest', 'account')),
  subject_id   uuid        NOT NULL,
  vn_day       date        NOT NULL,
  check_hash   bytea       NOT NULL,
  ip_hash      bytea,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (subject_kind, subject_id, vn_day, check_hash)
);

CREATE INDEX number_check_usage_ip ON number_check_usage (vn_day, ip_hash);
