ALTER TABLE player_logins
  ADD COLUMN last_attack BIGINT NOT NULL DEFAULT 0,
  ADD INDEX player_logins_last_attack_idx (last_attack);
