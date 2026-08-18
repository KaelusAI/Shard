CREATE TABLE IF NOT EXISTS mitigation_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server TEXT NOT NULL DEFAULT 'server',
  uuid TEXT NOT NULL,
  player_name TEXT NOT NULL DEFAULT '',
  rule TEXT NOT NULL,
  tier TEXT NOT NULL,
  score REAL NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS mitigation_events_uuid_ended_at_idx
  ON mitigation_events (uuid, ended_at);
CREATE INDEX IF NOT EXISTS mitigation_events_ended_at_idx ON mitigation_events (ended_at);
