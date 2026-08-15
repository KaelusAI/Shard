ALTER TABLE player_logins ADD COLUMN mitigation_score REAL NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN mitigation_score_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN mitigation_sessions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN mitigation_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_logins ADD COLUMN mitigation_last_day INTEGER NOT NULL DEFAULT 0;
