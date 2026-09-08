CREATE TABLE platform_run_budgets (
  run_id VARCHAR(128) PRIMARY KEY,
  max_model_steps INTEGER NOT NULL,
  max_duration_seconds INTEGER NOT NULL,
  max_tokens BIGINT NOT NULL,
  max_cost_microusd BIGINT NOT NULL,
  deadline BIGINT NOT NULL,
  used_tokens BIGINT NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
  reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
  used_cost_microusd BIGINT NOT NULL DEFAULT 0 CHECK (used_cost_microusd >= 0),
  reserved_cost_microusd BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cost_microusd >= 0),
  model_attempts BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE platform_model_decisions (
  decision_id VARCHAR(200) PRIMARY KEY,
  run_id VARCHAR(128) NOT NULL REFERENCES platform_run_budgets(run_id),
  fingerprint VARCHAR(64) NOT NULL,
  output VARCHAR(8192)
);

CREATE TABLE platform_model_attempts (
  decision_id VARCHAR(200) NOT NULL REFERENCES platform_model_decisions(decision_id),
  attempt_number INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','UNKNOWN','COMPLETED')),
  input_allowance BIGINT NOT NULL,
  output_allowance BIGINT NOT NULL,
  cost_allowance BIGINT NOT NULL,
  used_tokens BIGINT,
  used_cost_microusd BIGINT,
  reserved_at BIGINT NOT NULL,
  settled_at BIGINT,
  PRIMARY KEY (decision_id, attempt_number)
);
