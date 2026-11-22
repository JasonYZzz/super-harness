CREATE TABLE skill_versions (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  version VARCHAR(80) NOT NULL,
  content TEXT NOT NULL,
  checksum CHAR(64) NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  author VARCHAR(120) NOT NULL,
  UNIQUE(tenant_id,name,version),
  FOREIGN KEY(tenant_id,author) REFERENCES memberships(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE memory_entries (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  owner_id VARCHAR(120) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES memberships(tenant_id,user_id),
  INDEX memory_recent(tenant_id,owner_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE model_usage (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  run_id BINARY(16) NOT NULL,
  attempt INT NOT NULL,
  model VARCHAR(120) NOT NULL,
  reserved BIGINT NOT NULL,
  input_tokens INT,
  output_tokens INT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(tenant_id,run_id) REFERENCES agent_runs(tenant_id,id),
  INDEX daily_budget(tenant_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
