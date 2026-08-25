ALTER TABLE violations
  ADD COLUMN ai_buffer DOUBLE NULL,
  ADD COLUMN mitigation_score DOUBLE NULL,
  ADD COLUMN ai_windows BIGINT NULL,
  ADD COLUMN ai_high_windows BIGINT NULL,
  ADD COLUMN probability_trail VARBINARY(1024) NULL;
