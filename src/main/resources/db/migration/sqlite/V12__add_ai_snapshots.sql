ALTER TABLE violations ADD COLUMN ai_buffer REAL;
ALTER TABLE violations ADD COLUMN mitigation_score REAL;
ALTER TABLE violations ADD COLUMN ai_windows INTEGER;
ALTER TABLE violations ADD COLUMN ai_high_windows INTEGER;
