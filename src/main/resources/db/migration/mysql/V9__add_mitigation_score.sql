ALTER TABLE player_logins
  ADD COLUMN mitigation_score DOUBLE NOT NULL DEFAULT 0,
  ADD COLUMN mitigation_score_at BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN mitigation_sessions INT NOT NULL DEFAULT 0,
  ADD COLUMN mitigation_days INT NOT NULL DEFAULT 0,
  ADD COLUMN mitigation_last_day BIGINT NOT NULL DEFAULT 0;
