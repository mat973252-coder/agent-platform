CREATE TABLE platform_executions (
  operation_id VARCHAR(160) PRIMARY KEY,
  approval_id VARCHAR(160) NOT NULL UNIQUE REFERENCES platform_approvals(approval_id),
  fingerprint VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status IN ('IN_PROGRESS','SUCCEEDED','UNKNOWN','CLOSED_UNKNOWN')),
  reason_code VARCHAR(80) NOT NULL,
  output VARCHAR(4096),
  started_at BIGINT NOT NULL,
  finished_at BIGINT,
  closed_by VARCHAR(128),
  closed_reason VARCHAR(512),
  reconciliation_version BIGINT NOT NULL DEFAULT 0,
  delivered_version BIGINT NOT NULL DEFAULT 0
);

-- A controlled downstream with its own transactions and idempotency contract.
CREATE TABLE demo_service_ledger (
  service VARCHAR(128) PRIMARY KEY,
  generation BIGINT NOT NULL
);
INSERT INTO demo_service_ledger(service,generation) VALUES ('orders',0);

CREATE TABLE demo_restart_receipts (
  operation_id VARCHAR(160) PRIMARY KEY,
  fingerprint VARCHAR(64) NOT NULL,
  service VARCHAR(128) NOT NULL,
  generation BIGINT NOT NULL,
  committed_at BIGINT NOT NULL
);
