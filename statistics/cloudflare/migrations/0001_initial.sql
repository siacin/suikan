CREATE TABLE IF NOT EXISTS installations (
  id TEXT PRIMARY KEY, first_day TEXT NOT NULL,
  last_day TEXT NOT NULL, app_version TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS active (
  day TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(day,id)
);
CREATE TABLE IF NOT EXISTS installation_access (
  id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
  reason TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS installations_first ON installations(first_day);
CREATE INDEX IF NOT EXISTS installations_last ON installations(last_day DESC,first_day DESC,id);
CREATE INDEX IF NOT EXISTS active_id ON active(id);
