CREATE TABLE document_libraries (
  id BINARY(16) PRIMARY KEY,
  tenant_id VARCHAR(80) NOT NULL,
  owner_id VARCHAR(120) NOT NULL,
  name VARCHAR(120) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX library_owner(tenant_id,owner_id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES memberships(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE library_documents (
  id BINARY(16) PRIMARY KEY,
  library_id BINARY(16) NOT NULL,
  name VARCHAR(180) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(library_id) REFERENCES document_libraries(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE conversations ADD COLUMN library_id BINARY(16),
  ADD FOREIGN KEY(library_id) REFERENCES document_libraries(id) ON DELETE SET NULL;
