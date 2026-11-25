-- Q&A readings (paid questions and free chats). Question/answer text is AES-GCM ciphertext only.
CREATE TABLE qa_reading (
  id uuid PRIMARY KEY,
  subject_kind text NOT NULL CHECK (subject_kind IN ('guest', 'account')),
  subject_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('PAID', 'FREE')),
  topic text NOT NULL
    CHECK (topic IN ('TinhDuyen', 'SuNghiep', 'TaiLoc', 'SucKhoe', 'GiaDao', 'VanNam')),
  lang text NOT NULL,
  chart_hash text NOT NULL,
  status text NOT NULL
    CHECK (status IN
      ('PENDING', 'ANSWERED', 'RETRYING', 'REFUND_PENDING', 'REFUNDED', 'REFUSED', 'FAILED')),
  refusal_reason text NULL,
  credit_source text NULL,
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NULL,
  retry_until timestamptz NULL,
  -- a code only: all_providers_exhausted, filter:<rule>, executor_full, ...
  last_error text NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  answered_at timestamptz NULL,
  refunded_at timestamptz NULL
);

CREATE INDEX qa_reading_subject_created_idx ON qa_reading (subject_id, created_at DESC);
CREATE INDEX qa_reading_status_next_idx ON qa_reading (status, next_attempt_at);
CREATE INDEX qa_reading_free_chart_idx ON qa_reading (chart_hash, created_at) WHERE kind = 'FREE';

-- Seq 0 is the question (client_message_id = reading id); seqs 1-2 are follow-ups.
-- prompt_enc: the encrypted prompt of a pending message, so the retry job can call the model
-- again without the birth data; cleared once the message is answered or refused.
CREATE TABLE qa_message (
  id bigserial PRIMARY KEY,
  reading_id uuid NOT NULL REFERENCES qa_reading ON DELETE CASCADE,
  seq smallint NOT NULL CHECK (seq BETWEEN 0 AND 2),
  client_message_id uuid NOT NULL UNIQUE,
  question_enc bytea NOT NULL,
  answer_enc bytea NULL,
  prompt_enc bytea NULL,
  provider text NULL,
  used_keys text[] NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  answered_at timestamptz NULL,
  UNIQUE (reading_id, seq)
);

-- First answers only. cache_key = SHA-256 hex of
-- chart_hash|topic|kind|lang|knowledge_version|PROMPT_VERSION|normalized sanitized question.
CREATE TABLE qa_answer_cache (
  cache_key text PRIMARY KEY,
  answer_enc bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
