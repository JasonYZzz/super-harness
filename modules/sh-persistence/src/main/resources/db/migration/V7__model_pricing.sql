ALTER TABLE model_usage ADD COLUMN currency CHAR(3),
  ADD COLUMN input_per_million DECIMAL(16,6), ADD COLUMN output_per_million DECIMAL(16,6);
