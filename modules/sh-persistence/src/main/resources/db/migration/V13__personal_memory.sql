ALTER TABLE memory_entries
  ADD COLUMN source VARCHAR(12) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN agent_id BINARY(16),
  ADD COLUMN conversation_id BINARY(16),
  ADD COLUMN expires_at DATETIME(6),
  ADD COLUMN chat_digest BINARY(32) GENERATED ALWAYS AS
    (IF(source='CHAT',UNHEX(SHA2(content,256)),NULL)) STORED,
  ADD UNIQUE INDEX memory_chat_fact(tenant_id,owner_id,chat_digest),
  ADD COLUMN summary_conversation BINARY(16) GENERATED ALWAYS AS
    (IF(source='SUMMARY',conversation_id,NULL)) STORED,
  ADD COLUMN legacy_path VARCHAR(240),
  ADD COLUMN legacy_part INT,
  ADD UNIQUE INDEX memory_conversation_summary(summary_conversation),
  ADD INDEX memory_legacy_file(conversation_id,legacy_path,legacy_part),
  ADD INDEX memory_expiry(expires_at);
ALTER TABLE agent_runs ADD COLUMN pending_memory JSON;
