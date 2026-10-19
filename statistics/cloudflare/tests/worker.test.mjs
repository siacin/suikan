import {test,before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import worker,{handle,chinaDay,installationHash} from '../src/worker.mjs';

const TOKEN='cloudflare-local-test-'+ 'x'.repeat(36),TODAY='2026-10-09',NOW=Date.parse(TODAY+'T12:00:00+08:00');
const A='0f6cdb36-3e0a-4537-91d6-9ca6a840d341',B='3f6cdb36-3e0a-4537-91d6-9ca6a840d341';
let mf,db,env,writes;
function observed(database) {
  return {
    withSession: mode=>observed(database.withSession(mode)),
    prepare(sql) {
      const statement=database.prepare(sql);
      function wrapped(s) {return {bind:(...args)=>wrapped(s.bind(...args)),first:(...args)=>s.first(...args),all:(...args)=>s.all(...args),
        run:async()=>{const r=await s.run();writes+=r.meta.rows_written;return r;},_statement:s};}
      return wrapped(statement);
    },
    batch:async statements=>{const r=await database.batch(statements.map(s=>s._statement));for(const item of r)writes+=item.meta.rows_written;return r;}
  };
}
const allow={limit:async()=>({success:true})};
function payload(id=A,version='0.1.9',days=[TODAY]) {return {schema:1,installation_id:id,app_version:version,active_days:days};}
function req(path,body,admin=false,headers={}) {
  return new Request('https://local.test'+path,{method:body===undefined?'GET':'POST',headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(admin?{Authorization:'Bearer '+TOKEN}:{}),...headers},body:body===undefined?undefined:JSON.stringify(body)});
}
const call=(path,body,admin=false,headers={},bindings=env,now=NOW)=>handle(req(path,body,admin,headers),bindings,now);
async function json(path,body,admin=false,bindings=env) {const r=await call(path,body,admin,{},bindings);assert.equal(r.status,200);return r.json();}
async function access(id=A,version='0.1.9') {return json('/v1/access',{schema:1,installation_id:id,app_version:version});}
before(async()=>{
  const config=JSON.parse(await readFile(new URL('../wrangler.example.json',import.meta.url),'utf8'));
  const options=convertV4MiniflareOptions({name:'suikan-test-worker',modulesRoot:fileURLToPath(new URL('../',import.meta.url)),
    modules:['worker.mjs','assets.mjs'].map(name=>({type:'ESModule',path:fileURLToPath(new URL('../src/'+name,import.meta.url))})),compatibilityDate:config.compatibility_date,
    bindings:{SUIKAN_ADMIN_TOKEN:TOKEN},d1Databases:{DB:'suikan-test'},ratelimits:Object.fromEntries(config.ratelimits.map(r=>[r.name,{namespace_id:r.namespace_id,simple:r.simple}]))});
  options.telemetry={enabled:false};mf=new Miniflare(options);
  db=await mf.getD1Database('DB');
  for(const name of ['0001_initial.sql','0002_operations.sql']){
    const schema=await readFile(new URL('../migrations/'+name,import.meta.url),'utf8');
    for(const sql of schema.split(';').map(s=>s.trim()).filter(Boolean))await db.prepare(sql).run();
  }
});
beforeEach(async()=>{
  await db.batch(['DELETE FROM operation_daily','DELETE FROM operation_totals','DELETE FROM installation_access','DELETE FROM active','DELETE FROM installations'].map(sql=>db.prepare(sql)));
  writes=0;env={SUIKAN_ADMIN_TOKEN:TOKEN,DB:observed(db),CLIENT_LIMITER:allow,ADMIN_LIMITER:allow,PUBLIC_LIMITER:allow};
});
after(async()=>{await mf?.dispose();});

test('actual workerd runtime serves the Android protocol and bundled dashboard',async()=>{
  assert.equal((await mf.dispatchFetch('http://local.test/healthz')).status,200);
  const r=await mf.dispatchFetch('http://local.test/v1/access',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({schema:1,installation_id:A,app_version:'0.1.9'})});
  assert.deepEqual(await r.json(),{schema:1,allowed:true,reason:''});
  const home=await mf.dispatchFetch('http://local.test/');assert.match(await home.text(),/随看使用统计/);
  assert.match(home.headers.get('Content-Security-Policy'),/frame-ancestors 'none'/);
  const id=await installationHash(A);
  const changed=await mf.dispatchFetch('http://local.test/api/access',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+TOKEN},body:JSON.stringify({id,enabled:false,reason:'runtime test'})});
  assert.equal(changed.status,200);
  const denied=await mf.dispatchFetch('http://local.test/v1/access',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({schema:1,installation_id:A,app_version:'0.1.9'})});
  assert.deepEqual(await denied.json(),{schema:1,allowed:false,reason:'runtime test'});
});
test('actual Workers rate-limit binding enforces the configured client threshold',async()=>{
  const statuses=[];
  for(let i=0;i<13;i++)statuses.push((await mf.dispatchFetch('http://local.test/v1/access',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({schema:1,installation_id:B,app_version:'0.1.9'})})).status);
  assert.equal(statuses[0],200);assert.equal(statuses.at(-1),429);
});
test('Beijing calendar changes at 16:00 UTC',()=>{
  assert.equal(chinaDay(Date.parse('2026-10-08T15:59:59Z')),'2026-10-08');
  assert.equal(chinaDay(Date.parse('2026-10-08T16:00:00Z')),TODAY);
});
test('first access registers exactly one hashed installation and active day',async()=>{
  assert.deepEqual(await access(),{schema:1,allowed:true,reason:''});
  const s=await json('/api/summary',undefined,true);assert.equal(s.total_installations,1);assert.equal(s.new_today,1);assert.equal(s.dau,1);assert.equal(s.mau_30d,1);
  const list=await json('/api/installations',undefined,true);assert.equal(list.items[0].id,await installationHash(A));assert.equal(JSON.stringify(list).includes(A),false);
});
test('100 unchanged minute checks perform zero D1 row writes',async()=>{
  await access();writes=0;for(let i=0;i<100;i++)await access();assert.equal(writes,0);
});
test('duplicate daily usage is idempotent and does not rewrite unchanged rows',async()=>{
  assert.equal((await call('/v1/usage',payload())).status,204);writes=0;
  for(let i=0;i<5;i++)assert.equal((await call('/v1/usage',payload())).status,204);
  assert.equal(writes,0);assert.equal((await json('/api/summary',undefined,true)).dau,1);
});
test('offline backfill revises first date and counts 30-day activity correctly',async()=>{
  await access();await call('/v1/usage',payload(A,'0.1.8',['2026-09-09','2026-09-10','2026-10-08',TODAY]));
  await call('/v1/usage',payload(B,'0.1.9',['2026-10-08']));
  const s=await json('/api/summary',undefined,true);assert.equal(s.total_installations,2);assert.equal(s.new_today,0);assert.equal(s.dau,1);assert.equal(s.mau_30d,2);assert.equal(s.daily.length,30);assert.equal(s.daily[0].day,'2026-09-10');
  const item=(await json('/api/installations?q='+await installationHash(A),undefined,true)).items[0];assert.equal(item.first_day,'2026-09-09');
});
test('expired and future dates are acknowledged without adding installations',async()=>{
  assert.equal((await call('/v1/usage',payload(A,'0.1.9',['2026-09-08','2026-10-10']))).status,204);
  assert.equal((await json('/api/summary',undefined,true)).total_installations,0);
});
test('new-day access counts once and an old offline report cannot regress the version',async()=>{
  await call('/v1/usage',payload(A,'0.1.8',['2026-10-08']));await access(A,'0.1.9');
  await call('/v1/usage',payload(A,'0.1.7',['2026-10-07']));
  const item=(await json('/api/installations',undefined,true)).items[0];assert.equal(item.app_version,'0.1.9');assert.equal(item.last_day,TODAY);
  assert.equal((await json('/api/summary',undefined,true)).dau,1);
});
test('admin disable is seen by the next server check and restore clears reason',async()=>{
  await access();const id=await installationHash(A);
  await json('/api/access',{id,enabled:false,reason:'  测试停用  '},true);
  assert.deepEqual(await access(),{schema:1,allowed:false,reason:'测试停用'});
  assert.equal((await json('/api/installations',undefined,true)).items[0].enabled,false);
  await json('/api/access',{id,enabled:true,reason:'ignored'},true);
  assert.deepEqual(await access(),{schema:1,allowed:true,reason:''});
});
test('anonymous clients cannot modify permissions or read administration data',async()=>{
  await access();assert.equal((await call('/api/summary')).status,401);
  assert.equal((await call('/api/access',{id:await installationHash(A),enabled:false,reason:''})).status,401);
  for(const header of ['Bearer wrong','Bearer café','x'.repeat(600)])assert.equal((await call('/api/summary',undefined,false,{Authorization:header})).status,401);
  assert.equal((await call('/v1/access',{schema:1,installation_id:A,app_version:'0.1.9',allowed:false})).status,400);
  assert.equal((await access()).allowed,true);
});
test('unknown installations and malformed permission changes are rejected',async()=>{
  const id=await installationHash(A);assert.equal((await call('/api/access',{id,enabled:false,reason:''},true)).status,404);
  await access();
  for(const body of [{id,enabled:0,reason:''},{id,enabled:false,reason:'x'.repeat(201)},{id,enabled:false,reason:'bad\nreason'},{id:id+'\n',enabled:false,reason:''}])assert.equal((await call('/api/access',body,true)).status,400);
});
test('all JSON and field limits reject invalid input without writing rows',async()=>{
  for(const body of [null,[],{...payload(),schema:true},{...payload(),schema:2},{...payload(),installation_id:A+'\n'},
    {...payload(),app_version:'0.1.9\n'},{...payload(),app_version:'x'},{...payload(),active_days:[]},
    {...payload(),active_days:Array(32).fill(TODAY)},{...payload(),active_days:['2026-02-30']},{...payload(),active_days:[TODAY+'\n']},
    {...payload(),video:'not-collected'}])assert.equal((await call('/v1/usage',body)).status,400);
  assert.equal((await call('/v1/usage',payload(),false,{'Content-Type':'text/plain'})).status,415);
  assert.equal((await call('/v1/usage',{extra:'x'.repeat(8200)})).status,413);
  assert.equal((await handle(new Request('https://local.test/v1/usage',{method:'POST',headers:{'Content-Type':'application/json'},body:'{broken'}),env,NOW)).status,400);
  assert.equal(writes,0);
});
test('indexed prefix lookup, pagination and strict query validation',async()=>{
  await access(A);await access(B);const id=await installationHash(A);
  const page=await json('/api/installations?limit=1&offset=1',undefined,true);assert.equal(page.total,2);assert.equal(page.items.length,1);
  const exact=await json('/api/installations?q='+id,undefined,true);assert.equal(exact.total,1);assert.equal(exact.items[0].id,id);
  assert.equal((await json('/api/installations?q='+id.slice(0,12),undefined,true)).total,1);
  for(const q of ['limit=101','offset=-1','limit=0','q=ABC','x=1','q=a&q=b','offset=1.5','offset=1%0A','q=a%0A','q='+ 'f'.repeat(300)])assert.equal((await call('/api/installations?'+q,undefined,true)).status,400);
});
test('concurrent delivery counts an installation/day only once',async()=>{
  const r=await Promise.all(Array.from({length:10},()=>call('/v1/usage',payload())));assert.ok(r.every(x=>x.status===204));
  const s=await json('/api/summary',undefined,true);assert.equal(s.total_installations,1);assert.equal(s.dau,1);
});
test('daily cleanup retains 90 days and keeps cumulative installations and permissions',async()=>{
  await access();const id=await installationHash(A);await json('/api/access',{id,enabled:false,reason:'retained'},true);
  await db.batch(['2026-07-11','2026-07-12'].map(day=>db.prepare('INSERT INTO active VALUES(?,?)').bind(day,id)));
  await worker.scheduled({scheduledTime:NOW},{DB:db});
  const dates=(await db.prepare('SELECT day FROM active ORDER BY day').all()).results.map(r=>r.day);
  assert.deepEqual(dates,['2026-07-12',TODAY]);assert.equal((await access()).allowed,false);
});
test('unconfigured and failed database return bounded errors with no internal details',async()=>{
  assert.equal((await call('/healthz',undefined,false,{}, {...env,SUIKAN_ADMIN_TOKEN:''})).status,503);
  const broken={...env,DB:{withSession(){throw new Error(TOKEN+' private/database/path');}}};
  const r=await call('/v1/access',{schema:1,installation_id:A,app_version:'0.1.9'},false,{},broken);
  assert.equal(r.status,503);assert.deepEqual(await r.json(),{error:'temporarily_unavailable'});
});
test('rate limiting rejects before database work and sends retry interval',async()=>{
  const r=await call('/v1/usage',payload(),false,{}, {...env,PUBLIC_LIMITER:{limit:async()=>({success:false})}});
  assert.equal(r.status,429);assert.equal(r.headers.get('Retry-After'),'60');assert.equal(writes,0);
});
test('only the four dashboard assets are exposed; no CORS or private files',async()=>{
  for(const path of ['/','/dashboard.js','/dashboard.css','/favicon.svg']){const r=await call(path);assert.equal(r.status,200);assert.equal(r.headers.get('Cache-Control'),'no-store');assert.equal(r.headers.get('Access-Control-Allow-Origin'),null);}
  for(const path of ['/server.py','/.local/admin-token.txt','/wrangler.json','/migrations/0001_initial.sql'])assert.equal((await call(path)).status,404);
  for(const path of ['/v1/usage','/v1/access'])assert.equal((await call(path)).status,405);
});

function operations(extract=[2,1,1,0],summary=[3,1,1,1],day=TODAY,id=A){
  return {...payload(id,'0.1.11',[]),schema:2,operation_days:[{day,extract,summary}]};
}
test('workerd serves schema 2 and the real D1 operation queries',async()=>{
  const id='9f6cdb36-3e0a-4537-91d6-9ca6a840d341';
  const response=await mf.dispatchFetch('http://local.test/v1/usage',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(operations([1,1,0,0],[1,0,1,0],chinaDay(),id))});
  assert.equal(response.status,204);
  const summary=await mf.dispatchFetch('http://local.test/api/summary',{headers:{Authorization:'Bearer '+TOKEN}});
  assert.equal((await summary.json()).operations.total.started,2);
});
test('reapplying additive migration preserves installation, access and existing operation totals',async()=>{
  await call('/v1/usage',operations());const id=await installationHash(A);
  await json('/api/access',{id,enabled:false,reason:'preserve'},true);
  const schema=await readFile(new URL('../migrations/0002_operations.sql',import.meta.url),'utf8');
  for(const sql of schema.split(';').map(s=>s.trim()).filter(Boolean))await db.prepare(sql).run();
  assert.equal((await access()).allowed,false);assert.equal((await json('/api/summary',undefined,true)).operations.total.started,5);
});
test('schema 2 counts operation starts, outcomes, daily series and per-installation totals',async()=>{
  assert.equal((await call('/v1/usage',operations())).status,204);
  const s=await json('/api/summary',undefined,true);
  assert.equal(s.total_installations,1);assert.equal(s.dau,1);assert.equal(s.operations.total.started,5);
  assert.deepEqual(s.operations.total,s.operations.today);assert.equal(s.operations.today.succeeded,2);
  assert.equal(s.daily.at(-1).usage.extract.started,2);assert.equal(s.daily.at(-1).usage.summary.cancelled,1);
  assert.deepEqual((await json('/api/installations',undefined,true)).items[0].usage,s.operations.total);
});
test('duplicate, lost-ack and older operation snapshots do not rewrite or inflate counts',async()=>{
  await call('/v1/usage',operations());writes=0;
  for(let i=0;i<3;i++)await call('/v1/usage',operations());
  await call('/v1/usage',operations([1,0,0,0],[1,0,0,0]));
  assert.equal(writes,0);assert.equal((await json('/api/summary',undefined,true)).operations.total.started,5);
  await call('/v1/usage',operations([3,2,1,0],[3,2,1,1]));
  const total=(await json('/api/summary',undefined,true)).operations.total;
  assert.equal(total.started,6);assert.equal(total.succeeded,4);assert.equal(total.failed,2);assert.equal(total.cancelled,1);
});
test('concurrent different snapshots merge maxima and add only positive deltas',async()=>{
  const values=[operations([3,1,0,0],[4,2,0,0]),operations([2,2,0,0],[2,1,1,0]),operations([1,0,1,0],[1,0,0,1])];
  const responses=await Promise.all(Array.from({length:9},(_,i)=>call('/v1/usage',values[i%3])));
  assert.ok(responses.every(r=>r.status===204));const total=(await json('/api/summary',undefined,true)).operations.total;
  assert.deepEqual(total.extract,{started:3,succeeded:2,failed:1,cancelled:0});
  assert.deepEqual(total.summary,{started:4,succeeded:2,failed:1,cancelled:1});
});
test('offline operation dates, two installations and zero old-client history',async()=>{
  await call('/v1/usage',payload(B));await call('/v1/usage',operations());
  await call('/v1/usage',operations([1,1,0,0],[0,0,0,0],'2026-10-08'));
  await call('/v1/usage',operations([100,100,0,0],[100,100,0,0],'2026-09-08'));
  await call('/v1/usage',operations([100,100,0,0],[100,100,0,0],'2026-10-10'));
  const s=await json('/api/summary',undefined,true);assert.equal(s.total_installations,2);assert.equal(s.operations.total.started,6);assert.equal(s.operations.today.started,5);assert.equal(s.daily.at(-2).usage.started,1);
  const old=(await json('/api/installations?q='+await installationHash(B),undefined,true)).items[0];assert.equal(old.usage.started,0);
  await call('/v1/usage',operations([1,1,0,0],[1,0,1,0],TODAY,B));assert.equal((await json('/api/summary',undefined,true)).operations.total.started,8);
});
test('malformed counters, unknown content fields and duplicate dates are rejected atomically',async()=>{
  const base=operations();
  for(const value of [-1,1.5,true,'2',1000000001,null]){
    const p=structuredClone(base);p.operation_days[0].extract[0]=value;assert.equal((await call('/v1/usage',p)).status,400);
  }
  for(const patch of [{schema:1},{operation_days:null},{operation_days:Array(32).fill(base.operation_days[0])},{active_days:[],operation_days:[]}])assert.equal((await call('/v1/usage',{...base,...patch})).status,400);
  for(const patch of [{extract:[1,0]},{summary:'private video'},{day:'2026-02-30'},{video:'private video'}]){
    const p=structuredClone(base);Object.assign(p.operation_days[0],patch);assert.equal((await call('/v1/usage',p)).status,400);
  }
  const p=structuredClone(base);p.operation_days.push(p.operation_days[0]);assert.equal((await call('/v1/usage',p)).status,400);
  assert.equal(writes,0);
});
test('31 maximum-size daily snapshots fit limit and lifetime totals survive retention cleanup',async()=>{
  const p=operations();p.operation_days=Array.from({length:31},(_,i)=>({day:new Date(Date.parse(TODAY+'T00:00:00Z')-i*86400000).toISOString().slice(0,10),extract:Array(4).fill(1000000000),summary:Array(4).fill(1000000000)}));
  p.active_days=p.operation_days.map(r=>r.day);assert.ok(JSON.stringify(p).length<8192);
  assert.equal((await call('/v1/usage',p)).status,204);const before=(await json('/api/summary',undefined,true)).operations.total;
  assert.equal(before.started,62000000000);
  const future=NOW+120*86400000;await worker.scheduled({scheduledTime:future},{DB:db});
  assert.equal((await db.prepare('SELECT count(*) AS n FROM operation_daily').first()).n,0);
  const s=await (await call('/api/summary',undefined,true,{},env,future)).json();assert.equal(s.operations.today.started,0);assert.deepEqual(s.operations.total,before);
  assert.equal((await call('/v1/usage',p,false,{},env,future)).status,204);
  assert.deepEqual((await (await call('/api/summary',undefined,true,{},env,future)).json()).operations.total,before);
});
