ALTER TABLE violations ADD COLUMN ai_buffer REAL;
ALTER TABLE violations ADD COLUMN mitigation_score REAL;
ALTER TABLE violations ADD COLUMN ai_windows INTEGER;
ALTER TABLE violations ADD COLUMN ai_high_windows INTEGER;
ALTER TABLE violations ADD COLUMN probability_trail BLOB;

ALTER TABLE player_logins ADD COLUMN ai_high_windows INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN ai_windows INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN prob_low INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN prob_mid INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN prob_high INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN ai_state_at INTEGER NOT NULL DEFAULT 0;
