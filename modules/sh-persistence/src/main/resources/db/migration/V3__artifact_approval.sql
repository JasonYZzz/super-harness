CREATE TABLE artifacts (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  tool_execution_id BINARY(16) NOT NULL,
  object_key VARCHAR(200) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_length BIGINT NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  media_type VARCHAR(120) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE(tenant_id,run_id,tool_execution_id),
  FOREIGN KEY(tenant_id,run_id,tool_execution_id) REFERENCES tool_executions(tenant_id,run_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE audit_records (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  actor_id VARCHAR(120) NOT NULL,
  action VARCHAR(80) NOT NULL,
  resource_id BINARY(16) NOT NULL,
  details JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_audit(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
