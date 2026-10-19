import assets from './assets.mjs';

// The final assertion requires the end of input, including after a trailing newline.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![\s\S])/;
const VERSION = /^[0-9][0-9A-Za-z.+_-]{0,39}(?![\s\S])/;
const HASH = /^[0-9a-f]{64}(?![\s\S])/;
const encoder = new TextEncoder();
const DAY = 86400000;
const OUTCOMES=['started','succeeded','failed','cancelled'];
const COUNTERS=['extract','summary'].flatMap(kind=>OUTCOMES.map(outcome=>kind+'_'+outcome));
const sumColumns=COUNTERS.map(c=>`coalesce(sum(${c}),0) AS ${c}`).join(',');
function metrics(row={}) {
  const result={extract:{},summary:{}};
  for(const outcome of OUTCOMES){for(const kind of ['extract','summary'])result[kind][outcome]=row[kind+'_'+outcome]||0;result[outcome]=result.extract[outcome]+result.summary[outcome];}
  return result;
}
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'";
class HttpError extends Error {
  constructor(status, code) { super(code); this.status=status; }
}
function response(value, status=200, mime='application/json; charset=utf-8') {
  const headers = {'Content-Type':mime,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
    'Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','Content-Security-Policy':CSP};
  if (status===429) headers['Retry-After']='60';
  return new Response(status===204?null:(typeof value==='string'?value:JSON.stringify(value)),{status,headers});
}
function reject(status, code) { throw new HttpError(status,code); }
export function chinaDay(now=Date.now()) { return new Date(now+8*3600000).toISOString().slice(0,10); }
function shiftDay(day,n) { return new Date(Date.parse(day+'T00:00:00Z')+n*DAY).toISOString().slice(0,10); }
function validDay(day) {
  if (typeof day!=='string'||day.length!==10||!/^\d{4}-\d{2}-\d{2}$/.test(day)) return false;
  const n=Date.parse(day+'T00:00:00Z');
  return Number.isFinite(n)&&new Date(n).toISOString().slice(0,10)===day;
}
export async function installationHash(id) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode('suikan-usage-v1:'+id))),b=>b.toString(16).padStart(2,'0')).join('');
}
function exactKeys(value, keys) {
  return value!==null&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).sort().join(',')===keys.sort().join(',');
}
async function readJson(request,maxSize=2048) {
  if ((request.headers.get('Content-Type')||'').split(';')[0].trim().toLowerCase()!=='application/json') reject(415,'content_type');
  const length=request.headers.get('Content-Length');
  if (length!==null&&(!/^\d+$/.test(length)||Number(length)>maxSize||Number(length)<1)) reject(413,'body_size');
  if (!request.body) reject(400,'invalid_payload');
  const reader=request.body.getReader(),chunks=[];let size=0;
  try {
    while(true) {
      const {done,value}=await reader.read();if(done) break;
      size+=value.length;if(size>maxSize){await reader.cancel();reject(413,'body_size');}chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  try {
    const bytes=new Uint8Array(size);let at=0;for(const part of chunks){bytes.set(part,at);at+=part.length;}
    return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
  } catch { reject(400,'invalid_payload'); }
}
function validateClient(value, usage) {
  const fields=['schema','installation_id','app_version'];if(usage)fields.push('active_days');
  const modern=usage&&value?.schema===2;if(modern)fields.push('operation_days');
  if(!exactKeys(value,fields)||(!modern&&value.schema!==1)||typeof value.installation_id!=='string'||!UUID.test(value.installation_id)||
      typeof value.app_version!=='string'||!VERSION.test(value.app_version)) reject(400,'invalid_payload');
  if(usage&&(!Array.isArray(value.active_days)||value.active_days.length<(modern?0:1)||value.active_days.length>31||!value.active_days.every(validDay))) reject(400,'invalid_payload');
  if(modern){
    const records=value.operation_days;
    if(!Array.isArray(records)||records.length>31||(!records.length&&!value.active_days.length))reject(400,'invalid_payload');
    const seen=new Set();
    for(const r of records){
      if(!exactKeys(r,['day','extract','summary'])||!validDay(r.day)||seen.has(r.day))reject(400,'invalid_payload');seen.add(r.day);
      for(const kind of ['extract','summary'])if(!Array.isArray(r[kind])||r[kind].length!==4||!r[kind].every(n=>Number.isInteger(n)&&n>=0&&n<=1000000000))reject(400,'invalid_payload');
    }
  }
}
function configured(env) {
  return typeof env.SUIKAN_ADMIN_TOKEN==='string'&&env.SUIKAN_ADMIN_TOKEN.length>=32&&env.SUIKAN_ADMIN_TOKEN.length<=256&&
    !env.SUIKAN_ADMIN_TOKEN.startsWith('replace-')&&!/[\x00-\x20\x7f]/.test(env.SUIKAN_ADMIN_TOKEN)&&env.DB;
}
async function authorized(request,env) {
  const supplied=encoder.encode(request.headers.get('Authorization')||'');if(supplied.length>512)return false;
  // Compare fixed-length digests without short-circuiting on a matching prefix.
  const [left,right]=await Promise.all([crypto.subtle.digest('SHA-256',supplied),crypto.subtle.digest('SHA-256',encoder.encode('Bearer '+env.SUIKAN_ADMIN_TOKEN))]);
  const a=new Uint8Array(left),b=new Uint8Array(right);let diff=0;for(let i=0;i<a.length;i++)diff|=a[i]^b[i];return diff===0;
}
async function limit(binding,key) { if(!binding||!(await binding.limit({key})).success)reject(binding?429:503,binding?'rate_limited':'temporarily_unavailable'); }
function primary(env) { return env.DB.withSession('first-primary'); }
async function current(db,id) {
  return db.prepare('SELECT i.first_day,i.last_day,i.app_version,coalesce(a.enabled,1) AS enabled,coalesce(a.reason,\'\') AS reason FROM installations i LEFT JOIN installation_access a ON i.id=a.id WHERE i.id=?').bind(id).first();
}
async function record(db,id,version,days,known=undefined) {
  if(!days.length)return;
  const state=known===undefined?await current(db,id):known;
  const sorted=[...new Set(days)].sort(),first=sorted[0],last=sorted.at(-1),statements=[];
  // Checking an unchanged installation is read-only. Indexed columns are not rewritten every minute.
  if(!state||first<state.first_day||last>state.last_day||(last>=state.last_day&&version!==state.app_version)) {
    statements.push(db.prepare(`INSERT INTO installations VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET
      first_day=min(first_day,excluded.first_day),
      app_version=CASE WHEN excluded.last_day>=last_day THEN excluded.app_version ELSE app_version END,
      last_day=max(last_day,excluded.last_day)`).bind(id,first,last,version));
  }
  const existing=await db.prepare('SELECT day FROM active WHERE id=? AND day BETWEEN ? AND ?').bind(id,first,last).all();
  const present=new Set(existing.results.map(r=>r.day));
  for(const day of sorted)if(!present.has(day))statements.push(db.prepare('INSERT OR IGNORE INTO active(day,id) VALUES(?,?)').bind(day,id));
  // D1 batches are atomic; retries cannot create duplicate daily activity.
  if(statements.length)await db.batch(statements);
}
async function client(request,env,path,today) {
  await limit(env.PUBLIC_LIMITER,'client');
  const usage=path==='/v1/usage',value=await readJson(request,usage?8192:2048);validateClient(value,usage);
  const id=await installationHash(value.installation_id);await limit(env.CLIENT_LIMITER,id);
  const db=primary(env),state=await current(db,id);
  if(usage) {
    const earliest=shiftDay(today,-30),operations=(value.operation_days||[]).filter(r=>r.day>=earliest&&r.day<=today);
    const days=[...value.active_days,...operations.map(r=>r.day)].filter(d=>d>=earliest&&d<=today);
    await record(db,id,value.app_version,days,state);await recordOperations(db,id,operations);return response(null,204);
  }
  if(!state||state.last_day<today||state.app_version!==value.app_version)await record(db,id,value.app_version,[today],state);
  return response({schema:1,allowed:state?!!state.enabled:true,reason:state&&!state.enabled?state.reason:''});
}
async function recordOperations(db,id,records) {
  if(!records.length)return;
  const previous=await db.prepare('SELECT * FROM operation_daily WHERE id=? AND day BETWEEN ? AND ?').bind(id,records.map(r=>r.day).sort()[0],records.map(r=>r.day).sort().at(-1)).all();
  const known=new Map(previous.results.map(r=>[r.day,r])),statements=[];
  for(const r of records){
    const values=[...r.extract,...r.summary],old=known.get(r.day)||{};
    if(values.every((n,i)=>n<=(old[COUNTERS[i]]||0)))continue;
    // Each atomic pair reads the current daily row, adds only positive deltas, then takes maxima.
    // Concurrent, repeated and older snapshots therefore commute without counting twice.
    statements.push(db.prepare(`INSERT INTO operation_totals(id,${COUNTERS.join(',')})
      SELECT ?,${COUNTERS.map(c=>`max(?-coalesce(d.${c},0),0)`).join(',')}
      FROM (SELECT 1) LEFT JOIN operation_daily d ON d.day=? AND d.id=? WHERE true
      ON CONFLICT(id) DO UPDATE SET ${COUNTERS.map(c=>`${c}=${c}+excluded.${c}`).join(',')}`).bind(id,...values,r.day,id));
    statements.push(db.prepare(`INSERT INTO operation_daily(day,id,${COUNTERS.join(',')}) VALUES(${Array(10).fill('?').join(',')})
      ON CONFLICT(day,id) DO UPDATE SET ${COUNTERS.map(c=>`${c}=max(${c},excluded.${c})`).join(',')}`).bind(r.day,id,...values));
  }
  if(statements.length)await db.batch(statements);
}
async function listInstallations(db,url) {
  if(url.search.length>257)reject(400,'invalid_query');
  const args=url.searchParams;
  for(const key of args.keys())if(!['offset','limit','q'].includes(key)||args.getAll(key).length!==1)reject(400,'invalid_query');
  const rawOffset=args.get('offset')??'0',rawLimit=args.get('limit')??'50',q=args.get('q')??'';
  const offset=Number(rawOffset),count=Number(rawLimit);
  if(!/^\d+(?![\s\S])/.test(rawOffset)||!/^\d+(?![\s\S])/.test(rawLimit)||offset>1000000000||count<1||count>100||!/^[0-9a-f]{0,64}(?![\s\S])/.test(q))reject(400,'invalid_query');
  const where=q?'i.id>=? AND i.id<?':'1=1',values=q?[q,q+'g']:[];
  const result=await db.batch([
    db.prepare(`SELECT count(*) AS total FROM installations i WHERE ${where}`).bind(...values),
    db.prepare(`SELECT i.id,i.first_day,i.last_day,i.app_version,coalesce(a.enabled,1) AS enabled,coalesce(a.reason,'') AS reason,${COUNTERS.map(c=>'coalesce(t.'+c+',0) AS '+c).join(',')}
      FROM installations i LEFT JOIN installation_access a ON i.id=a.id LEFT JOIN operation_totals t ON i.id=t.id WHERE ${where}
      ORDER BY i.last_day DESC,i.first_day DESC,i.id LIMIT ? OFFSET ?`).bind(...values,count,offset)
  ]);
  return response({total:result[0].results[0].total,offset,limit:count,items:result[1].results.map(r=>({id:r.id,first_day:r.first_day,last_day:r.last_day,app_version:r.app_version,enabled:!!r.enabled,reason:r.reason,usage:metrics(r)}))});
}
async function summary(db,today) {
  const start=shiftDay(today,-29);
  const r=await db.batch([
    db.prepare('SELECT count(*) AS n FROM installations'),
    db.prepare('SELECT count(*) AS n FROM installations WHERE first_day=?').bind(today),
    db.prepare('SELECT count(*) AS n FROM active WHERE day=?').bind(today),
    db.prepare('SELECT count(DISTINCT id) AS n FROM active WHERE day BETWEEN ? AND ?').bind(start,today),
    db.prepare('SELECT day,count(*) AS n FROM active WHERE day BETWEEN ? AND ? GROUP BY day').bind(start,today),
    db.prepare('SELECT first_day AS day,count(*) AS n FROM installations WHERE first_day BETWEEN ? AND ? GROUP BY first_day').bind(start,today),
    db.prepare('SELECT app_version AS version,count(*) AS installations FROM installations GROUP BY app_version ORDER BY installations DESC,version'),
    db.prepare(`SELECT ${sumColumns} FROM operation_totals`),
    db.prepare(`SELECT ${sumColumns} FROM operation_daily WHERE day=?`).bind(today),
    db.prepare(`SELECT day,${sumColumns} FROM operation_daily WHERE day BETWEEN ? AND ? GROUP BY day`).bind(start,today)
  ]);
  const active=new Map(r[4].results.map(row=>[row.day,row.n])),fresh=new Map(r[5].results.map(row=>[row.day,row.n]));
  const uses=new Map(r[9].results.map(row=>[row.day,metrics(row)]));
  const daily=Array.from({length:30},(_,i)=>{const day=shiftDay(start,i);return {day,active:active.get(day)||0,new:fresh.get(day)||0,usage:uses.get(day)||metrics()};});
  return response({timezone:'Asia/Shanghai',date:today,total_installations:r[0].results[0].n,new_today:r[1].results[0].n,
    dau:r[2].results[0].n,mau_30d:r[3].results[0].n,daily,versions:r[6].results,operations:{total:metrics(r[7].results[0]),today:metrics(r[8].results[0])}});
}
async function changeAccess(db,request) {
  const value=await readJson(request);
  if(!exactKeys(value,['id','enabled','reason'])||typeof value.id!=='string'||!HASH.test(value.id)||typeof value.enabled!=='boolean'||
    typeof value.reason!=='string'||value.reason.length>200||/[\x00-\x1f]/.test(value.reason))reject(400,'invalid_payload');
  const r=await db.prepare(`INSERT INTO installation_access(id,enabled,reason) SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=?)
    ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,reason=excluded.reason`).bind(value.id,value.enabled?1:0,value.enabled?'':value.reason.trim(),value.id).run();
  if(r.meta.changes===0)reject(404,'installation_not_found');
  return response({ok:true});
}
export async function handle(request,env,now=Date.now()) {
  try {
    const url=new URL(request.url),path=url.pathname,method=request.method;
    if(assets[path]&&method==='GET')return response(assets[path].body,200,assets[path].mime);
    if(path==='/healthz') {
      if(method!=='GET')reject(405,'method_not_allowed');
      if(!configured(env))reject(503,'not_configured');
      await primary(env).prepare('SELECT id FROM installations LIMIT 1').first();
      await primary(env).prepare('SELECT id FROM operation_totals LIMIT 1').first();
      return response({ok:true});
    }
    const publicPath=['/v1/usage','/v1/access'].includes(path),adminPath=['/api/summary','/api/installations','/api/access'].includes(path);
    if(!publicPath&&!adminPath)reject(404,'not_found');
    if(!configured(env))reject(503,'not_configured');
    if(publicPath) {
      if(method!=='POST')reject(405,'method_not_allowed');
      return await client(request,env,path,chinaDay(now));
    }
    // IP is used transiently for per-location rate limiting, never written to D1.
    await limit(env.ADMIN_LIMITER,await installationHash(request.headers.get('CF-Connecting-IP')||'local'));
    if(!await authorized(request,env))reject(401,'unauthorized');
    const db=primary(env);
    if(path==='/api/summary'&&method==='GET')return await summary(db,chinaDay(now));
    if(path==='/api/installations'&&method==='GET')return await listInstallations(db,url);
    if(path==='/api/access'&&method==='POST')return await changeAccess(db,request);
    reject(405,'method_not_allowed');
  } catch(error) {
    // Never return credentials, raw client IDs, request bodies or D1 errors to clients.
    return response({error:error instanceof HttpError?error.message:'temporarily_unavailable'},error instanceof HttpError?error.status:503);
  }
}
export default {
  fetch:(request,env)=>handle(request,env),
  async scheduled(event,env) {
    const oldest=shiftDay(chinaDay(event.scheduledTime),-89);
    await env.DB.batch(['active','operation_daily'].map(table=>env.DB.prepare(`DELETE FROM ${table} WHERE day<?`).bind(oldest)));
  }
};
