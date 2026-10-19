-- Additive video task history. Re-running this migration preserves existing data.
BEGIN;
CREATE TABLE IF NOT EXISTS suikan.video_analysis (
 id text NOT NULL REFERENCES suikan.installations(id), event_id uuid NOT NULL,
 client text NOT NULL CHECK(client IN ('android','browser')), app_version text NOT NULL,
 started_at bigint NOT NULL, completed_at bigint NOT NULL,
 status text NOT NULL CHECK(status IN ('started','succeeded','failed','cancelled')),
 depth text NOT NULL CHECK(depth IN ('concise','standard','detailed')), cached boolean NOT NULL,
 bvid text NOT NULL, aid bigint NOT NULL, cid bigint NOT NULL, page integer NOT NULL, title text NOT NULL,
 PRIMARY KEY(id,event_id)
);
CREATE INDEX IF NOT EXISTS video_analysis_time ON suikan.video_analysis(started_at DESC);
CREATE INDEX IF NOT EXISTS video_analysis_video ON suikan.video_analysis(bvid,cid,started_at DESC);
CREATE TABLE IF NOT EXISTS suikan.video_maintenance(id integer PRIMARY KEY CHECK(id=1),prune_day text NOT NULL);
REVOKE ALL ON suikan.video_analysis,suikan.video_maintenance FROM PUBLIC;
-- Same registration semantics as 0002; also supports a standalone PostgreSQL deployment.
CREATE OR REPLACE FUNCTION suikan.record(i text,v text,days text[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE first text; last text; previous record;
BEGIN
 SELECT min(x),max(x) INTO first,last FROM unnest(days) x;
 IF first IS NULL THEN RETURN; END IF;
 SELECT * INTO previous FROM suikan.installations WHERE id=i;
 IF NOT FOUND OR first<previous.first_day OR last>previous.last_day OR (last>=previous.last_day AND v<>previous.app_version) THEN
  INSERT INTO suikan.installations AS t VALUES(i,first,last,v) ON CONFLICT(id) DO UPDATE SET
   first_day=least(t.first_day,excluded.first_day),last_day=greatest(t.last_day,excluded.last_day),
   app_version=CASE WHEN excluded.last_day>=t.last_day THEN excluded.app_version ELSE t.app_version END;
 END IF;
 INSERT INTO suikan.active(day,id) SELECT DISTINCT x,i FROM unnest(days) x
  WHERE NOT EXISTS(SELECT 1 FROM suikan.active a WHERE a.day=x AND a.id=i) ON CONFLICT DO NOTHING;
END $$;
REVOKE ALL ON FUNCTION suikan.record(text,text,text[]) FROM PUBLIC;
CREATE OR REPLACE FUNCTION suikan.video_metrics(r jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
 SELECT jsonb_build_object('attempts',coalesce((r->>'attempts')::bigint,0),'succeeded',coalesce((r->>'succeeded')::bigint,0),'failed',coalesce((r->>'failed')::bigint,0),'cancelled',coalesce((r->>'cancelled')::bigint,0),'unfinished',coalesce((r->>'unfinished')::bigint,0),'cached',coalesce((r->>'cached')::bigint,0),'users',coalesce((r->>'users')::bigint,0),'videos',coalesce((r->>'videos')::bigint,0)) $$;
CREATE OR REPLACE FUNCTION suikan.video_rpc(action text,p jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE i text:=p->>'id'; e jsonb; old suikan.video_analysis%ROWTYPE; today text:=p->>'today';
 result jsonb; items jsonb; totals jsonb; total bigint; lo bigint; hi bigint;
BEGIN
 IF action='ingest' THEN
  PERFORM pg_advisory_xact_lock(hashtextextended(i,0));
  PERFORM suikan.record(i,p->>'version',ARRAY(SELECT DISTINCT to_char(to_timestamp((x->>'started_at')::bigint/1000.0) AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD') FROM jsonb_array_elements(p->'events') x));
  FOR e IN SELECT value FROM jsonb_array_elements(p->'events') LOOP
   SELECT * INTO old FROM suikan.video_analysis WHERE id=i AND event_id=(e->>'event_id')::uuid;
   IF FOUND THEN
    IF old.started_at<>(e->>'started_at')::bigint OR old.bvid<>e->>'bvid' OR old.aid<>(e->>'aid')::bigint OR old.cid<>(e->>'cid')::bigint OR old.page<>(e->>'page')::integer OR old.depth<>e->>'depth' OR old.client<>p->>'client' THEN
     RAISE EXCEPTION 'event identity conflict';
    END IF;
    IF old.status='started' AND e->>'status'<>'started' THEN
     UPDATE suikan.video_analysis SET status=e->>'status',completed_at=(e->>'completed_at')::bigint,cached=(e->>'cached')::boolean WHERE id=i AND event_id=old.event_id;
    END IF;
   ELSE
    INSERT INTO suikan.video_analysis VALUES(i,(e->>'event_id')::uuid,p->>'client',p->>'version',(e->>'started_at')::bigint,(e->>'completed_at')::bigint,e->>'status',e->>'depth',(e->>'cached')::boolean,e->>'bvid',(e->>'aid')::bigint,(e->>'cid')::bigint,(e->>'page')::integer,e->>'title');
   END IF;
  END LOOP;
  PERFORM pg_advisory_xact_lock(-832640012439002::bigint);
  IF NOT EXISTS(SELECT 1 FROM suikan.video_maintenance WHERE id=1 AND prune_day>=today) THEN
   DELETE FROM suikan.video_analysis WHERE started_at<(extract(epoch FROM ((today::date-89)::timestamp AT TIME ZONE 'Asia/Shanghai'))*1000)::bigint;
   INSERT INTO suikan.video_maintenance VALUES(1,today) ON CONFLICT(id) DO UPDATE SET prune_day=excluded.prune_day;
  END IF;
  RETURN jsonb_build_object('ok',true);
 ELSIF action IN ('videos','events') THEN
  lo:=(extract(epoch FROM ((p->>'from')::date::timestamp AT TIME ZONE 'Asia/Shanghai'))*1000)::bigint;
  hi:=(extract(epoch FROM (((p->>'to')::date+1)::timestamp AT TIME ZONE 'Asia/Shanghai'))*1000)::bigint;
  WITH filtered AS (SELECT * FROM suikan.video_analysis WHERE started_at>=lo AND started_at<hi
   AND (p->>'q'='' OR (id>=p->>'q' AND id<(p->>'q')||'g')) AND (p->>'bvid'='' OR bvid=p->>'bvid') AND (p->>'client'='' OR client=p->>'client'))
  SELECT suikan.video_metrics(jsonb_build_object('attempts',count(*),'succeeded',count(*) FILTER(WHERE status='succeeded'),'failed',count(*) FILTER(WHERE status='failed'),'cancelled',count(*) FILTER(WHERE status='cancelled'),'unfinished',count(*) FILTER(WHERE status='started'),'cached',count(*) FILTER(WHERE cached),'users',count(DISTINCT id),'videos',count(DISTINCT (bvid,cid,page)))) INTO totals FROM filtered;
  IF action='videos' THEN
   WITH filtered AS (SELECT * FROM suikan.video_analysis WHERE started_at>=lo AND started_at<hi AND (p->>'q'='' OR (id>=p->>'q' AND id<(p->>'q')||'g')) AND (p->>'bvid'='' OR bvid=p->>'bvid') AND (p->>'client'='' OR client=p->>'client')),
   grouped AS (SELECT bvid,cid,page,(array_agg(title ORDER BY started_at DESC,event_id))[1] title,max(started_at) last_at,count(*) attempts,count(*) FILTER(WHERE status='succeeded') succeeded,count(*) FILTER(WHERE status='failed') failed,count(*) FILTER(WHERE status='cancelled') cancelled,count(*) FILTER(WHERE status='started') unfinished,count(*) FILTER(WHERE cached) cached,count(DISTINCT id) users FROM filtered GROUP BY bvid,cid,page),
   paged AS (SELECT * FROM grouped ORDER BY last_at DESC,bvid,cid,page LIMIT (p->>'limit')::integer OFFSET (p->>'offset')::integer)
   SELECT (SELECT count(*) FROM grouped),coalesce(jsonb_agg(to_jsonb(paged)||jsonb_build_object('url','https://www.bilibili.com/video/'||bvid||'/?p='||page) ORDER BY last_at DESC,bvid,cid,page),'[]'::jsonb) INTO total,items FROM paged;
  ELSE
   WITH filtered AS (SELECT * FROM suikan.video_analysis WHERE started_at>=lo AND started_at<hi AND (p->>'q'='' OR (id>=p->>'q' AND id<(p->>'q')||'g')) AND (p->>'bvid'='' OR bvid=p->>'bvid') AND (p->>'client'='' OR client=p->>'client')),
   paged AS (SELECT * FROM filtered ORDER BY started_at DESC,id,event_id LIMIT (p->>'limit')::integer OFFSET (p->>'offset')::integer)
   SELECT (SELECT count(*) FROM filtered),coalesce(jsonb_agg(to_jsonb(paged)||jsonb_build_object('url','https://www.bilibili.com/video/'||bvid||'/?p='||page) ORDER BY started_at DESC,id,event_id),'[]'::jsonb) INTO total,items FROM paged;
  END IF;
  RETURN jsonb_build_object('total',total,'items',items,'totals',totals,'offset',(p->>'offset')::integer,'limit',(p->>'limit')::integer,'from',p->>'from','to',p->>'to');
 END IF;
 RAISE EXCEPTION 'unknown video action';
END $$;
CREATE OR REPLACE FUNCTION public.suikan_video_statistics_rpc(action text,p jsonb) RETURNS jsonb LANGUAGE sql SECURITY INVOKER AS $$ SELECT suikan.video_rpc(action,p) $$;
REVOKE ALL ON FUNCTION suikan.video_metrics(jsonb),suikan.video_rpc(text,jsonb),public.suikan_video_statistics_rpc(text,jsonb) FROM PUBLIC;
DO $$ DECLARE role_name text; BEGIN
 FOR role_name IN SELECT rolname FROM pg_roles WHERE rolname IN ('service_role','cloudbase_postgres') LOOP
  EXECUTE format('GRANT USAGE ON SCHEMA suikan TO %I',role_name);
  EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON suikan.video_analysis,suikan.video_maintenance TO %I',role_name);
  EXECUTE format('GRANT EXECUTE ON FUNCTION suikan.record(text,text,text[]),suikan.video_metrics(jsonb),suikan.video_rpc(text,jsonb),public.suikan_video_statistics_rpc(text,jsonb) TO %I',role_name);
 END LOOP;
END $$;
COMMIT;
