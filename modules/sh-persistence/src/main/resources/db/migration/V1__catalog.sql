CREATE TABLE tenants (
  id VARCHAR(80) PRIMARY KEY,
  max_open_runs INT NOT NULL DEFAULT 100,
  CHECK(max_open_runs > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE memberships (
  tenant_id VARCHAR(80) NOT NULL,
  user_id VARCHAR(120) NOT NULL,
  role VARCHAR(20) NOT NULL,
  PRIMARY KEY(tenant_id,user_id),
  FOREIGN KEY(tenant_id) REFERENCES tenants(id),
  CHECK(role IN ('OWNER','EDITOR','MEMBER','APPROVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_definitions (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  owner_id VARCHAR(120) NOT NULL,
  name VARCHAR(120) NOT NULL,
  archived BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES memberships(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_revisions (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  agent_id BINARY(16) NOT NULL,
  revision INT NOT NULL,
  prompt TEXT NOT NULL,
  model VARCHAR(120) NOT NULL,
  store_profile VARCHAR(120) NOT NULL,
  store_version INT NOT NULL,
  checksum CHAR(64) NOT NULL,
  config JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,agent_id,id),
  UNIQUE(tenant_id,agent_id,revision),
  FOREIGN KEY(tenant_id,agent_id) REFERENCES agent_definitions(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE conversations (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  owner_id VARCHAR(120) NOT NULL,
  agent_id BINARY(16) NOT NULL,
  revision_id BINARY(16) NOT NULL,
  state_slot BINARY(16),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,id,revision_id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES memberships(tenant_id,user_id),
  FOREIGN KEY(tenant_id,agent_id,revision_id) REFERENCES agent_revisions(tenant_id,agent_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
