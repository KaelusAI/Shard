ALTER TABLE player_logins ADD COLUMN last_attack INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS player_logins_last_attack_idx ON player_logins (last_attack);
