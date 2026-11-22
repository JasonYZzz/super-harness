CREATE TABLE channel_threads (
    tenant_id VARCHAR(80) NOT NULL,
    owner_id VARCHAR(120) NOT NULL,
    agent_id BINARY(16) NOT NULL,
    source VARCHAR(64) NOT NULL,
    thread_key VARCHAR(128) NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    PRIMARY KEY (tenant_id,owner_id,agent_id,source,thread_key),
    UNIQUE (conversation_id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
