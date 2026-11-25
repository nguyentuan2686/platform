CREATE TABLE llm_usage (
  provider text NOT NULL,
  day date NOT NULL,
  requests int NOT NULL DEFAULT 0,
  prompt_tokens bigint NOT NULL DEFAULT 0,
  completion_tokens bigint NOT NULL DEFAULT 0,
  cost_micro_usd bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (provider, day)
);

CREATE TABLE llm_provider_state (
  provider text PRIMARY KEY,
  exhausted_until timestamptz NOT NULL
);
