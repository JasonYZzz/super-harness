CREATE TABLE agent_schedules (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  owner_id VARCHAR(120) NOT NULL,
  agent_id BINARY(16) NOT NULL,
  revision_id BINARY(16) NOT NULL,
  settings JSON NOT NULL,
  model_override JSON NOT NULL,
  next_due DATETIME(6),
  retry_after DATETIME(6),
  last_error VARCHAR(80),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX schedule_due(next_due,retry_after),
  INDEX schedule_owner(tenant_id,owner_id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES memberships(tenant_id,user_id),
  FOREIGN KEY(tenant_id,agent_id,revision_id) REFERENCES agent_revisions(tenant_id,agent_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE conversations ADD COLUMN schedule_id BINARY(16),
  ADD INDEX conversation_schedule(tenant_id,owner_id,schedule_id);
