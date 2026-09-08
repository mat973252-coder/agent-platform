CREATE TABLE platform_approvals (
  approval_id VARCHAR(160) PRIMARY KEY,
  run_id VARCHAR(100) NOT NULL UNIQUE,
  operation_id VARCHAR(160) NOT NULL UNIQUE,
  step_id VARCHAR(64) NOT NULL,
  tool_name VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  expires_at BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVE', 'REJECT', 'CANCELLED')),
  decided_by VARCHAR(128),
  decided_at BIGINT,
  cancelled_by VARCHAR(128),
  cancelled_at BIGINT,
  execution_started BOOLEAN NOT NULL DEFAULT FALSE,
  delivered BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX platform_approvals_delivery ON platform_approvals(status, delivered);
