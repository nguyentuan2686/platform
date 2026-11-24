CREATE TABLE knowledge (
  id             bigserial PRIMARY KEY,
  key            text        NOT NULL,
  school         text        NOT NULL,
  lang           text        NOT NULL,
  topic          text,
  content        text        NOT NULL,
  source_ref     text        NOT NULL,
  status         text        NOT NULL CHECK (status IN ('draft', 'approved', 'hidden')),
  version        int         NOT NULL,
  content_sha256 text        NOT NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (key, school, lang)
);

CREATE TABLE knowledge_meta (
  id      int    PRIMARY KEY CHECK (id = 1),
  version bigint NOT NULL
);
INSERT INTO knowledge_meta (id, version) VALUES (1, 0);

CREATE TABLE basic_reading_cache (
  chart_hash        text,
  knowledge_version bigint,
  body              jsonb       NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (chart_hash, knowledge_version)
);
