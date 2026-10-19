-- Execute in the CloudBase PostgreSQL SQL console as the environment administrator.
BEGIN;
CREATE SCHEMA IF NOT EXISTS suikan;
REVOKE ALL ON SCHEMA suikan FROM PUBLIC;
CREATE TABLE IF NOT EXISTS suikan.installations (
  id text PRIMARY KEY CHECK(id ~ '^[0-9a-f]{64}$'),
  first_day text NOT NULL, last_day text NOT NULL, app_version text NOT NULL
);
CREATE TABLE IF NOT EXISTS suikan.active (
  day text NOT NULL, id text NOT NULL REFERENCES suikan.installations(id), PRIMARY KEY(day,id)
);
CREATE TABLE IF NOT EXISTS suikan.installation_access (
  id text PRIMARY KEY REFERENCES suikan.installations(id),
  enabled integer NOT NULL CHECK(enabled IN(0,1)), reason text NOT NULL
);
CREATE TABLE IF NOT EXISTS suikan.operation_daily (
  day text NOT NULL, id text NOT NULL REFERENCES suikan.installations(id),
  extract_started bigint NOT NULL DEFAULT 0 CHECK(extract_started>=0),
  extract_succeeded bigint NOT NULL DEFAULT 0 CHECK(extract_succeeded>=0),
  extract_failed bigint NOT NULL DEFAULT 0 CHECK(extract_failed>=0),
  extract_cancelled bigint NOT NULL DEFAULT 0 CHECK(extract_cancelled>=0),
  summary_started bigint NOT NULL DEFAULT 0 CHECK(summary_started>=0),
  summary_succeeded bigint NOT NULL DEFAULT 0 CHECK(summary_succeeded>=0),
  summary_failed bigint NOT NULL DEFAULT 0 CHECK(summary_failed>=0),
  summary_cancelled bigint NOT NULL DEFAULT 0 CHECK(summary_cancelled>=0),
  PRIMARY KEY(day,id)
);
CREATE TABLE IF NOT EXISTS suikan.operation_totals (
  id text PRIMARY KEY REFERENCES suikan.installations(id),
  extract_started bigint NOT NULL DEFAULT 0 CHECK(extract_started>=0),
  extract_succeeded bigint NOT NULL DEFAULT 0 CHECK(extract_succeeded>=0),
  extract_failed bigint NOT NULL DEFAULT 0 CHECK(extract_failed>=0),
  extract_cancelled bigint NOT NULL DEFAULT 0 CHECK(extract_cancelled>=0),
  summary_started bigint NOT NULL DEFAULT 0 CHECK(summary_started>=0),
  summary_succeeded bigint NOT NULL DEFAULT 0 CHECK(summary_succeeded>=0),
  summary_failed bigint NOT NULL DEFAULT 0 CHECK(summary_failed>=0),
  summary_cancelled bigint NOT NULL DEFAULT 0 CHECK(summary_cancelled>=0)
);
CREATE TABLE IF NOT EXISTS suikan.maintenance (id integer PRIMARY KEY CHECK(id=1), prune_day text NOT NULL);
CREATE INDEX IF NOT EXISTS installations_first ON suikan.installations(first_day);
CREATE INDEX IF NOT EXISTS installations_last ON suikan.installations(last_day DESC,first_day DESC,id);
CREATE INDEX IF NOT EXISTS active_id ON suikan.active(id);
CREATE INDEX IF NOT EXISTS operation_daily_id ON suikan.operation_daily(id);
REVOKE ALL ON ALL TABLES IN SCHEMA suikan FROM PUBLIC;
-- This is CloudBase's backend login role, not an Android or browser credential.
-- For a separate runtime role, grant it the same privileges in the SQL console.
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cloudbase_postgres') THEN
    GRANT USAGE ON SCHEMA suikan TO cloudbase_postgres;
    GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA suikan TO cloudbase_postgres;
  END IF;
END $$;
COMMIT;
