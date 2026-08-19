CREATE TABLE IF NOT EXISTS mitigation_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  server VARCHAR(255) NOT NULL DEFAULT 'server',
  uuid VARCHAR(36) NOT NULL,
  player_name VARCHAR(64) NOT NULL DEFAULT '',
  rule VARCHAR(255) NOT NULL,
  tier VARCHAR(16) NOT NULL,
  score DOUBLE NOT NULL,
  started_at BIGINT NOT NULL,
  ended_at BIGINT NOT NULL,
  PRIMARY KEY (id),
  INDEX mitigation_events_uuid_ended_at_idx (uuid, ended_at),
  INDEX mitigation_events_ended_at_idx (ended_at)
);
