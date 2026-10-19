import {test,before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {PGlite} from '@electric-sql/pglite';
import {PostgresStore} from '../functions/suikan-statistics/store.mjs';
import {createHandler} from '../functions/suikan-statistics/app.mjs';
import {createServer} from '../functions/suikan-statistics/http.mjs';
import {createPool} from '../functions/suikan-statistics/config.mjs';
import {chinaDay,installationHash,COUNTERS} from '../functions/suikan-statistics/protocol.mjs';
const TOKEN='cloudbase-local-check-'+ 'x'.repeat(40),TODAY='2026-10-10',NOW=Date.parse(TODAY+'T12:00:00+08:00');
const A='0f6cdb36-3e0a-4537-91d6-9ca6a840d341',B='3f6cdb36-3e0a-4537-91d6-9ca6a840d341';
const allow={allow:()=>true};let db,pool,store,handle,writes=0,failSql='';
// PGlite executes real PostgreSQL SQL in one embedded connection. Serialize whole
// transactions here, like a size-one pg pool. Multi-session MVCC needs a live PG test.
class EmbeddedPool {
  constructor(database){this.db=database;this.tail=Promise.resolve();}
  async connect(){
    const previous=this.tail;let release;this.tail=new Promise(resolve=>release=resolve);await previous;
    return {query:async(sql,args=[])=>{
      if(failSql&&sql.includes(failSql)){failSql='';throw new Error('injected database fault with private text');}
      const r=await this.db.query(sql,args);if(/^\s*(INSERT|UPDATE|DELETE)/i.test(sql))writes+=r.affectedRows||0;
      return {...r,rowCount:r.affectedRows??r.rows.length};
    },release};
  }
  async query(sql,args=[]){const c=await this.connect();try{return await c.query(sql,args);}finally{c.release();}}
}
const payload=(id=A,version='0.1.12',days=[TODAY])=>({schema:1,installation_id:id,app_version:version,active_days:days});
const modern=(extract=[1,0,0,0],summary=[0,0,0,0],day=TODAY)=>({...payload(),schema:2,operation_days:[{day,extract,summary}]});
const request=(path,body,admin=false,headers={})=>new Request('https://test.invalid'+path,{method:body===undefined?'GET':'POST',headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(admin?{Authorization:'Bearer '+TOKEN}:{}),...headers},body:body===undefined?undefined:JSON.stringify(body)});
const call=(path,body,admin=false,headers={})=>handle(request(path,body,admin,headers));
async function json(path,body,admin=false){const r=await call(path,body,admin);assert.equal(r.status,200,await r.clone().text());return r.json();}
const access=(id=A,version='0.1.12')=>json('/v1/access',{schema:1,installation_id:id,app_version:version});
const stats=()=>json('/api/summary',undefined,true);
before(async()=>{db=new PGlite();await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));pool=new EmbeddedPool(db);});
beforeEach(async()=>{
  await db.exec('TRUNCATE suikan.active,suikan.installation_access,suikan.operation_daily,suikan.operation_totals,suikan.installations,suikan.maintenance CASCADE');
  store=new PostgresStore(pool);handle=createHandler({store,token:TOKEN,clock:()=>NOW,limiter:allow});writes=0;failSql='';
});
after(async()=>{await db?.close();});
test('schema is repeatable and PostgreSQL advisory lock SQL executes',async()=>{
  await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));
  await pool.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',['installation-test']);
  assert.equal((await call('/healthz')).status,200);
});
test('Beijing dates have the same boundary as the Android client',()=>{
  assert.equal(chinaDay(Date.parse('2026-10-09T15:59:59Z')),'2026-10-09');assert.equal(chinaDay(Date.parse('2026-10-09T16:00:00Z')),TODAY);
});
test('registers one hashed installation and preserves old APK protocol',async()=>{
  assert.deepEqual(await access(A,'0.1.10'),{schema:1,allowed:true,reason:''});
  assert.equal((await call('/v1/usage',payload(A,'0.1.10'))).status,204);
  const s=await stats();assert.equal(s.total_installations,1);assert.equal(s.dau,1);assert.equal(s.new_today,1);
  const list=await json('/api/installations',undefined,true);assert.equal(list.items[0].id,installationHash(A));assert.equal(JSON.stringify(list).includes(A),false);
});
test('unchanged access checks and duplicate snapshots perform zero row writes',async()=>{
  await access();await call('/v1/usage',modern());writes=0;
  for(let i=0;i<20;i++){await access();assert.equal((await call('/v1/usage',modern())).status,204);}
  assert.equal(writes,0);assert.equal((await stats()).operations.total.started,1);
});
test('counts both features and all outcomes using monotonic cumulative snapshots',async()=>{
  assert.equal((await call('/v1/usage',modern([3,1,1,1],[5,2,2,1]))).status,204);
  const s=await stats();assert.equal(s.operations.total.started,8);assert.equal(s.operations.total.succeeded,3);assert.equal(s.operations.total.failed,3);assert.equal(s.operations.total.cancelled,2);
  assert.deepEqual(s.operations.today,s.operations.total);assert.equal(s.daily.at(-1).usage.started,8);
  const item=(await json('/api/installations',undefined,true)).items[0];assert.equal(item.usage.summary.started,5);
});
test('repeated, older and simultaneous callers do not inflate daily or lifetime counts',async()=>{
  await Promise.all([modern([4,3,0,0],[2,1,1,0]),modern([1,1,0,0]),modern([4,2,1,0],[1,1,0,0]),modern([2,0,0,0])].map(p=>call('/v1/usage',p)));
  const s=await stats();assert.equal(s.operations.total.started,6);assert.equal(s.operations.total.extract.succeeded,3);assert.equal(s.operations.total.extract.failed,1);
  assert.equal(s.operations.total.summary.started,2);assert.equal(s.operations.total.summary.failed,1);
});
test('failure between lifetime and daily updates rolls back the whole report',async()=>{
  await call('/v1/usage',modern([2,1,0,0]));failSql='INSERT INTO suikan.operation_daily';
  const r=await call('/v1/usage',modern([5,4,0,0]));assert.equal(r.status,503);assert.deepEqual(await r.json(),{error:'temporarily_unavailable'});
  assert.equal((await stats()).operations.total.started,2);
  assert.equal((await call('/v1/usage',modern([5,4,0,0]))).status,204);assert.equal((await stats()).operations.total.started,5);
});
test('offline dates and stale versions preserve first date and most recent version',async()=>{
  await access();await call('/v1/usage',payload(A,'0.1.9',['2026-09-10','2026-09-11','2026-10-09']));
  const s=await stats(),i=(await json('/api/installations',undefined,true)).items[0];
  assert.equal(i.first_day,'2026-09-10');assert.equal(i.last_day,TODAY);assert.equal(i.app_version,'0.1.12');assert.equal(s.new_today,0);assert.equal(s.daily[0].day,'2026-09-11');
});
test('expired and future snapshots do not create installations or counters',async()=>{
  const p=modern([1,1,0,0],[0,0,0,0],'2026-09-09');p.active_days=['2026-09-09','2026-10-11'];
  assert.equal((await call('/v1/usage',p)).status,204);assert.equal((await stats()).total_installations,0);
});
test('31-day backfill includes boundary date and separate completion date',async()=>{
  const p=modern([1,0,0,0],[0,0,0,0],'2026-09-10');p.operation_days.push({day:TODAY,extract:[0,1,0,0],summary:[0,0,0,0]});p.active_days=[];
  assert.equal((await call('/v1/usage',p)).status,204);const s=await stats();assert.equal(s.operations.total.started,1);assert.equal(s.operations.today.started,0);assert.equal(s.operations.today.succeeded,1);
});
test('disable and restore are visible in next access check without changing counts',async()=>{
  await call('/v1/usage',modern());const id=installationHash(A);
  await json('/api/access',{id,enabled:false,reason:'  测试停用  '},true);assert.deepEqual(await access(),{schema:1,allowed:false,reason:'测试停用'});
  await json('/api/access',{id,enabled:true,reason:'ignored'},true);assert.deepEqual(await access(),{schema:1,allowed:true,reason:''});assert.equal((await stats()).operations.total.started,1);
});
test('cannot change a nonexistent installation',async()=>{assert.equal((await call('/api/access',{id:installationHash(A),enabled:false,reason:''},true)).status,404);});
test('dashboard and all data routes require correct configuration/authentication',async()=>{
  for(const path of ['/api/summary','/api/installations','/api/access'])assert.equal((await call(path)).status,401);
  const bad=createHandler({store,token:'bad',limiter:allow});assert.equal((await bad(request('/healthz'))).status,503);
  const home=await bad(request('/'));assert.equal(home.status,200);assert.match(await home.text(),/随看使用统计/);
  assert.match(home.headers.get('Content-Security-Policy'),/frame-ancestors 'none'/);assert.equal(home.headers.get('Cache-Control'),'no-store');
});
test('rejects malformed IDs, versions, extra fields and invalid calendar dates',async()=>{
  for(const p of [{...payload(),installation_id:A+'\n'},{...payload(),schema:true},{...payload(),app_version:'0.1.12\n'},{...payload(),api_key:'secret'},{...payload(),active_days:['2026-02-30']},{...payload(),active_days:[]},{...payload(),active_days:Array(32).fill(TODAY)}])assert.equal((await call('/v1/usage',p)).status,400);
  assert.equal((await call('/v1/access',{...payload()})).status,400);
});
test('rejects non-integer counters, duplicates, unknown fields and empty reports',async()=>{
  const cases=[modern([-1,0,0,0]),modern([1.5,0,0,0]),modern([1000000001,0,0,0]),modern([true,0,0,0]),modern(['1',0,0,0]),modern([1,0,0])];
  const duplicate=modern();duplicate.operation_days.push(duplicate.operation_days[0]);cases.push(duplicate);
  const extra=modern();extra.operation_days[0].title='private';cases.push(extra);cases.push({...modern(),active_days:[],operation_days:[]});
  for(const p of cases)assert.equal((await call('/v1/usage',p)).status,400);
});
test('strict UTF-8, content type and both advertised and streamed body limits',async()=>{
  assert.equal((await call('/v1/usage',payload(),false,{'Content-Type':'text/plain'})).status,415);
  assert.equal((await call('/v1/usage',payload(),false,{'Content-Length':'9000'})).status,413);
  const raw=bytes=>new Request('https://test.invalid/v1/usage',{method:'POST',headers:{'Content-Type':'application/json'},body:bytes});
  assert.equal((await handle(raw(' '.repeat(8193)))).status,413);assert.equal((await handle(raw(new Uint8Array([0xff,0xfe])))).status,400);
  assert.equal((await handle(raw('{'))).status,400);
});
test('rejects invalid pagination, repeated query keys and SQL-like input',async()=>{
  for(const q of ['?limit=101','?offset=-1','?limit=2&limit=3','?q=a%27','?offset=1%0a','?unknown=1'])assert.equal((await call('/api/installations'+q,undefined,true)).status,400);
  await access(A);await access(B);const id=installationHash(A);
  const list=await json('/api/installations?q='+id+'&limit=1',undefined,true);assert.equal(list.total,1);assert.equal(list.items[0].id,id);
  assert.equal((await json('/api/installations?offset=1&limit=1',undefined,true)).items.length,1);
});
test('permission payload rejects non-booleans, invalid hash and control characters',async()=>{
  await access();for(const p of [{id:installationHash(A),enabled:1,reason:''},{id:'bad',enabled:false,reason:''},{id:installationHash(A),enabled:false,reason:'x\n'},{id:installationHash(A),enabled:false,reason:'x'.repeat(201)}])assert.equal((await call('/api/access',p,true)).status,400);
});
test('lazy retention deletes 90-day details while keeping lifetime totals and permissions',async()=>{
  await access();const id=installationHash(A);await json('/api/access',{id,enabled:false,reason:'still disabled'},true);
  await store.ingest(id,'0.1.12',['2026-07-12','2026-07-13'],[{day:'2026-07-12',extract:[3,2,1,0],summary:[0,0,0,0]}]);
  await store.pruneIfDue(TODAY);assert.equal((await pool.query('SELECT day FROM suikan.active WHERE day<$1',['2026-07-13'])).rows.length,0);
  assert.equal((await pool.query('SELECT day FROM suikan.active WHERE day=$1',['2026-07-13'])).rows.length,1);
  assert.equal((await stats()).operations.total.started,3);assert.equal((await access()).allowed,false);
  writes=0;await store.pruneIfDue(TODAY);assert.equal(writes,0);
});
test('per-installation limiter and admin limiter return retry-after without leaking data',async()=>{
  handle=createHandler({store,token:TOKEN,clock:()=>NOW});let r;
  for(let i=0;i<13;i++)r=await call('/v1/access',{schema:1,installation_id:A,app_version:'0.1.12'});
  assert.equal(r.status,429);assert.equal(r.headers.get('Retry-After'),'60');
  for(let i=0;i<61;i++)r=await call('/api/summary');assert.equal(r.status,429);
});
test('unknown paths and wrong methods cannot expose configuration or source',async()=>{
  for(const path of ['/../config.mjs','/cloudbaserc.json','/.env','/scf_bootstrap','/api/secret'])assert.equal((await call(path)).status,404);
  assert.equal((await call('/v1/usage')).status,405);assert.equal((await call('/healthz',{})).status,405);
  assert.equal((await call('/api/summary',{},true)).status,405);
});
test('Node HTTP adapter handles real POSTs and serves shared admin assets',async()=>{
  const server=createServer(handle);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const base='http://127.0.0.1:'+server.address().port;
    const r=await fetch(base+'/v1/usage',{method:'POST',headers:{'Content-Type':'application/json','X-Cloudbase-Context':'private-secret'},body:JSON.stringify(modern([2,1,0,0]))});assert.equal(r.status,204);assert.equal(await r.text(),'');
    const s=await fetch(base+'/api/summary',{headers:{Authorization:'Bearer '+TOKEN}});assert.equal((await s.json()).operations.total.started,2);
    for(const path of ['/','/dashboard.js','/dashboard.css','/favicon.svg'])assert.equal((await fetch(base+path)).status,200);
    const bad=await fetch(base+'/v1/usage',{method:'POST',headers:{'Content-Type':'application/json'},body:'x'.repeat(9000)});assert.equal(bad.status,413);assert.equal((await bad.text()).includes('private-secret'),false);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
test('database connection config defaults to TLS verification and a bounded pool',async()=>{
  assert.throws(()=>createPool({}));const config={PGHOST:'localhost',PGDATABASE:'test',PGUSER:'test',PGPASSWORD:'local-fixture'};
  const p=createPool(config);assert.equal(p.options.ssl.rejectUnauthorized,true);assert.equal(p.options.max,3);assert.equal(p.options.connectionTimeoutMillis,2000);await p.end();
  assert.throws(()=>createPool({...config,PGSSLMODE:'prefer'}));
});
