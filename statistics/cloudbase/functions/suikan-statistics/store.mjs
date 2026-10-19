import {COUNTERS,metrics,shiftDay} from './protocol.mjs';
const columns=COUNTERS.join(','),sums=COUNTERS.map(c=>`coalesce(sum(${c}),0) AS ${c}`).join(',');
const MAINTENANCE_LOCK='-832640012439001';
function count(value){const n=Number(value);if(!Number.isSafeInteger(n)||n<0)throw new Error('Counter overflow');return n;}
export class PostgresStore {
  constructor(pool){this.pool=pool;this.lastPruneDay='';}
  async videoCall(action,p){return (await this.pool.query('SELECT suikan.video_rpc($1,$2::jsonb) AS value',[action,JSON.stringify(p)])).rows[0].value;}
  videoIngest(id,version,client,events,today){return this.videoCall('ingest',{id,version,client,events,today});}
  videoList(action,query){return this.videoCall(action,query);}
  async transaction(work,{snapshot=false,lockId}={}){
    const client=await this.pool.connect();let started=false;
    try{
      await client.query(snapshot?'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY':'BEGIN');started=true;
      await client.query("SET LOCAL statement_timeout = '5000ms'");
      await client.query("SET LOCAL lock_timeout = '2000ms'");
      if(lockId)await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[lockId]);
      const result=await work(client);await client.query('COMMIT');started=false;return result;
    }catch(error){if(started)try{await client.query('ROLLBACK');}catch{}throw error;}
    finally{client.release();}
  }
  async health(){await this.pool.query('SELECT (SELECT id FROM suikan.installations LIMIT 1) AS installation,(SELECT id FROM suikan.operation_totals LIMIT 1) AS operation');}
  async ingest(id,version,days,operations=[]){
    if(!days.length)return;
    return this.transaction(async c=>{
      await this.record(c,id,version,days);
      for(const r of [...operations].sort((a,b)=>a.day.localeCompare(b.day))){
        const old=(await c.query('SELECT * FROM suikan.operation_daily WHERE day=$1 AND id=$2',[r.day,id])).rows[0]||{};
        const incoming=[...r.extract,...r.summary],delta=incoming.map((n,i)=>Math.max(n-count(old[COUNTERS[i]]||0),0));
        if(!delta.some(Boolean))continue;
        await c.query(`INSERT INTO suikan.operation_totals AS t(id,${columns}) VALUES(${Array.from({length:9},(_,i)=>'$'+(i+1)).join(',')})
          ON CONFLICT(id) DO UPDATE SET ${COUNTERS.map(col=>`${col}=t.${col}+excluded.${col}`).join(',')}`,[id,...delta]);
        await c.query(`INSERT INTO suikan.operation_daily AS d(day,id,${columns}) VALUES(${Array.from({length:10},(_,i)=>'$'+(i+1)).join(',')})
          ON CONFLICT(day,id) DO UPDATE SET ${COUNTERS.map(col=>`${col}=greatest(d.${col},excluded.${col})`).join(',')}`,[r.day,id,...incoming]);
      }
    },{lockId:id});
  }
  async record(c,id,version,days){
    const sorted=[...new Set(days)].sort(),first=sorted[0],last=sorted.at(-1);
    const previous=(await c.query('SELECT first_day,last_day,app_version FROM suikan.installations WHERE id=$1',[id])).rows[0];
    if(!previous||first<previous.first_day||last>previous.last_day||(last>=previous.last_day&&version!==previous.app_version)){
      await c.query(`INSERT INTO suikan.installations AS i VALUES($1,$2,$3,$4) ON CONFLICT(id) DO UPDATE SET
        first_day=least(i.first_day,excluded.first_day),
        app_version=CASE WHEN excluded.last_day>=i.last_day THEN excluded.app_version ELSE i.app_version END,
        last_day=greatest(i.last_day,excluded.last_day)`,[id,first,last,version]);
    }
    const existing=new Set((await c.query('SELECT day FROM suikan.active WHERE id=$1 AND day BETWEEN $2 AND $3',[id,first,last])).rows.map(r=>r.day));
    const missing=sorted.filter(day=>!existing.has(day));
    if(missing.length)await c.query('INSERT INTO suikan.active(day,id) SELECT unnest($1::text[]),$2 ON CONFLICT DO NOTHING',[missing,id]);
  }
  async access(id,version,today){
    return this.transaction(async c=>{
      await this.record(c,id,version,[today]);
      const state=(await c.query('SELECT enabled,reason FROM suikan.installation_access WHERE id=$1',[id])).rows[0];
      return {schema:1,allowed:state?!!state.enabled:true,reason:state&&!state.enabled?state.reason:''};
    },{lockId:id});
  }
  async changeAccess(id,enabled,reason){
    return this.transaction(async c=>(await c.query(`INSERT INTO suikan.installation_access(id,enabled,reason)
      SELECT $1,$2,$3 WHERE EXISTS(SELECT 1 FROM suikan.installations WHERE id=$1)
      ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,reason=excluded.reason`,[id,enabled?1:0,enabled?'':reason.trim()])).rowCount>0,{lockId:id});
  }
  async installations({offset,limit,q}){
    return this.transaction(async c=>{
      const where=q?'i.id >= $1 AND i.id < $2':'true',args=q?[q,q+'g']:[];
      const total=count((await c.query(`SELECT count(*) AS n FROM suikan.installations i WHERE ${where}`,args)).rows[0].n);
      const rows=(await c.query(`SELECT i.*,coalesce(a.enabled,1) AS enabled,coalesce(a.reason,'') AS reason,
        ${COUNTERS.map(col=>`coalesce(t.${col},0) AS ${col}`).join(',')}
        FROM suikan.installations i LEFT JOIN suikan.installation_access a ON i.id=a.id
        LEFT JOIN suikan.operation_totals t ON i.id=t.id WHERE ${where}
        ORDER BY i.last_day DESC,i.first_day DESC,i.id LIMIT $${args.length+1} OFFSET $${args.length+2}`,[...args,limit,offset])).rows;
      return {total,offset,limit,items:rows.map(r=>({id:r.id,first_day:r.first_day,last_day:r.last_day,app_version:r.app_version,enabled:!!r.enabled,reason:r.reason,usage:metrics(r)}))};
    },{snapshot:true});
  }
  async summary(today){
    return this.transaction(async c=>{
      const start=shiftDay(today,-29),scalar=async(sql,args=[])=>(await c.query(sql,args)).rows[0];
      const total=count((await scalar('SELECT count(*) AS n FROM suikan.installations')).n);
      const fresh=count((await scalar('SELECT count(*) AS n FROM suikan.installations WHERE first_day=$1',[today])).n);
      const dau=count((await scalar('SELECT count(*) AS n FROM suikan.active WHERE day=$1',[today])).n);
      const mau=count((await scalar('SELECT count(DISTINCT id) AS n FROM suikan.active WHERE day BETWEEN $1 AND $2',[start,today])).n);
      const active=new Map((await c.query('SELECT day,count(*) AS n FROM suikan.active WHERE day BETWEEN $1 AND $2 GROUP BY day',[start,today])).rows.map(r=>[r.day,count(r.n)]));
      const added=new Map((await c.query('SELECT first_day AS day,count(*) AS n FROM suikan.installations WHERE first_day BETWEEN $1 AND $2 GROUP BY first_day',[start,today])).rows.map(r=>[r.day,count(r.n)]));
      const versions=(await c.query('SELECT app_version AS version,count(*) AS installations FROM suikan.installations GROUP BY app_version ORDER BY installations DESC,version')).rows.map(r=>({version:r.version,installations:count(r.installations)}));
      const all=metrics(await scalar(`SELECT ${sums} FROM suikan.operation_totals`));
      const now=metrics(await scalar(`SELECT ${sums} FROM suikan.operation_daily WHERE day=$1`,[today]));
      const uses=new Map((await c.query(`SELECT day,${sums} FROM suikan.operation_daily WHERE day BETWEEN $1 AND $2 GROUP BY day`,[start,today])).rows.map(r=>[r.day,metrics(r)]));
      const daily=Array.from({length:30},(_,i)=>{const day=shiftDay(start,i);return {day,active:active.get(day)||0,new:added.get(day)||0,usage:uses.get(day)||metrics()};});
      return {timezone:'Asia/Shanghai',date:today,total_installations:total,new_today:fresh,dau,mau_30d:mau,daily,versions,operations:{total:all,today:now}};
    },{snapshot:true});
  }
  // Run lazily on the first real report of each Beijing day; no background timer is required.
  async pruneIfDue(today){
    if(this.lastPruneDay===today)return;
    await this.transaction(async c=>{
      await c.query('SELECT pg_advisory_xact_lock($1::bigint)',[MAINTENANCE_LOCK]);
      const row=(await c.query('SELECT prune_day FROM suikan.maintenance WHERE id=1')).rows[0];
      if(!row||row.prune_day<today){
        const oldest=shiftDay(today,-89);
        await c.query('DELETE FROM suikan.active WHERE day<$1',[oldest]);
        await c.query('DELETE FROM suikan.operation_daily WHERE day<$1',[oldest]);
        await c.query('INSERT INTO suikan.maintenance VALUES(1,$1) ON CONFLICT(id) DO UPDATE SET prune_day=excluded.prune_day',[today]);
      }
    });
    this.lastPruneDay=today;
  }
}
