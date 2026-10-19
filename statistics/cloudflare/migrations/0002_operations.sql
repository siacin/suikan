CREATE TABLE IF NOT EXISTS operation_daily (
  day TEXT NOT NULL, id TEXT NOT NULL,
  extract_started INTEGER NOT NULL DEFAULT 0, extract_succeeded INTEGER NOT NULL DEFAULT 0,
  extract_failed INTEGER NOT NULL DEFAULT 0, extract_cancelled INTEGER NOT NULL DEFAULT 0,
  summary_started INTEGER NOT NULL DEFAULT 0, summary_succeeded INTEGER NOT NULL DEFAULT 0,
  summary_failed INTEGER NOT NULL DEFAULT 0, summary_cancelled INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(day,id)
);
CREATE TABLE IF NOT EXISTS operation_totals (
  id TEXT PRIMARY KEY,
  extract_started INTEGER NOT NULL DEFAULT 0, extract_succeeded INTEGER NOT NULL DEFAULT 0,
  extract_failed INTEGER NOT NULL DEFAULT 0, extract_cancelled INTEGER NOT NULL DEFAULT 0,
  summary_started INTEGER NOT NULL DEFAULT 0, summary_succeeded INTEGER NOT NULL DEFAULT 0,
  summary_failed INTEGER NOT NULL DEFAULT 0, summary_cancelled INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS operation_daily_id ON operation_daily(id);
