CREATE TABLE agent_runs (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  conversation_id BINARY(16) NOT NULL,
  agent_revision_id BINARY(16) NOT NULL,
  status VARCHAR(40) NOT NULL,
  request_key VARCHAR(180) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  next_retry_at DATETIME(6),
  attempt INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(120),
  lease_deadline DATETIME(6),
  fence BIGINT NOT NULL DEFAULT 0,
  next_seq BIGINT NOT NULL DEFAULT 0,
  requested_by VARCHAR(120) NOT NULL,
  input_json JSON NOT NULL,
  slot_id BINARY(16),
  deadline_at DATETIME(6) NOT NULL,
  FOREIGN KEY(tenant_id,conversation_id,agent_revision_id)
    REFERENCES conversations(tenant_id,id,revision_id),
  error_code VARCHAR(120),
  accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at DATETIME(6),
  finished_at DATETIME(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX idx_runs_queue ON agent_runs(status, accepted_at);
CREATE INDEX idx_runs_lease ON agent_runs(status, lease_deadline);
CREATE UNIQUE INDEX uq_run_request ON agent_runs(tenant_id, requested_by, request_key);

-- 只允许一个 Run 持有会话；暂停、审批和人工核验期间也保留。
CREATE TABLE conversation_gates (
  tenant_id VARCHAR(80) NOT NULL,
  conversation_id BINARY(16) NOT NULL,
  run_id BINARY(16) NOT NULL,
  PRIMARY KEY (tenant_id, conversation_id),
  FOREIGN KEY (tenant_id, conversation_id, run_id)
    REFERENCES agent_runs(tenant_id, conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE run_events (
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
  seq BIGINT NOT NULL,
  attempt INT NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE UNIQUE INDEX idx_events_tenant_run ON run_events(tenant_id, run_id, seq);

CREATE TABLE run_checkpoints (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
  attempt INT NOT NULL,
  boundary VARCHAR(60) NOT NULL,
  state_ref TEXT,
  workspace_ref TEXT,
  safe_to_replay BOOLEAN NOT NULL DEFAULT FALSE,
  metadata JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE tool_executions (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
  logical_step_id VARCHAR(120) NOT NULL,
  tool_name VARCHAR(180) NOT NULL,
  tool_version VARCHAR(100) NOT NULL,
  args_sha256 VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(220) NOT NULL,
  status VARCHAR(40) NOT NULL,
  result_ref TEXT,
  external_receipt TEXT,
  last_error TEXT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE(tenant_id, idempotency_key),
  UNIQUE(tenant_id, run_id, logical_step_id),
  UNIQUE(tenant_id, run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE approvals (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
  tool_execution_id BINARY(16) NOT NULL,
  FOREIGN KEY (tenant_id, run_id, tool_execution_id)
    REFERENCES tool_executions(tenant_id, run_id, id),
  requested_args_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(40) NOT NULL,
  requested_by VARCHAR(120) NOT NULL,
  decided_by VARCHAR(120),
  expires_at DATETIME(6) NOT NULL,
  decided_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE outbox (
  run_id BINARY(16) NOT NULL,
  seq BIGINT NOT NULL,
  tenant_id VARCHAR(80) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(run_id,seq),
  FOREIGN KEY(tenant_id,run_id,seq) REFERENCES run_events(tenant_id,run_id,seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
