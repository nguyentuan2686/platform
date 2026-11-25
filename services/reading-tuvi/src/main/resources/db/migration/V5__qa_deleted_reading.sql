-- Tombstones of deleted PAID readings. identity-svc's consume is idempotent per reading id (a
-- repeated id answers 200 without a second charge), so a PAID id must never be accepted again
-- after its history is deleted. Ids only: no question, answer, topic or chart data is kept.
CREATE TABLE qa_deleted_reading (
  id uuid PRIMARY KEY,
  subject_id uuid NOT NULL,
  deleted_at timestamptz NOT NULL DEFAULT now()
);
