import {test,before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {PGlite} from '@electric-sql/pglite';
import {RpcStore,cloudBaseRpc,assertSafe} from '../functions/suikan-statistics/rpc-store.mjs';
import {PostgresStore} from '../functions/suikan-statistics/store.mjs';
import {createHandler} from '../functions/suikan-statistics/app.mjs';
import {installationHash} from '../functions/suikan-statistics/protocol.mjs';
let db,store; const day='2026-10-10',id='a'.repeat(64),token='rpc-test-only-'+ 'x'.repeat(40);
before(async()=>{
 db=new PGlite();await db.exec('CREATE ROLE service_role; CREATE ROLE anon; CREATE ROLE authenticated;');
 for(const name of ['0001_schema.sql','0002_rpc.sql'])await db.exec(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8'));
});
beforeEach(async()=>{await db.exec('TRUNCATE suikan.active,suikan.installation_access,suikan.operation_daily,suikan.operation_totals,suikan.installations,suikan.maintenance CASCADE');
 store=new RpcStore(async(action,p)=>(await db.query('SELECT public.suikan_statistics_rpc($1,$2::jsonb) AS value',[action,JSON.stringify(p)])).rows[0].value);});
after(async()=>db?.close());
const op=(a=[3,1,1,1],b=[2,1,1,0],date=day)=>[{day:date,extract:a,summary:b}];
test('RPC migration can repeat without losing data',async()=>{await store.ingest(id,'0.1.12',[day],op());await db.exec(await readFile(new URL('../migrations/0002_rpc.sql',import.meta.url),'utf8'));assert.equal((await store.summary(day)).operations.total.started,5);});
test('RPC requires service role; anonymous and authenticated cannot call it',async()=>{
 for(const role of ['anon','authenticated'])assert.equal((await db.query("SELECT has_function_privilege($1,'public.suikan_statistics_rpc(text,jsonb)','EXECUTE') AS allowed",[role])).rows[0].allowed,false);
 assert.equal((await db.query("SELECT has_function_privilege('service_role','public.suikan_statistics_rpc(text,jsonb)','EXECUTE') AS allowed")).rows[0].allowed,true);
});
test('service role can read/write private schema through RPC',async()=>{await db.exec('SET ROLE service_role');try{await store.access(id,'0.1.12',day);assert.equal((await store.summary(day)).total_installations,1);}finally{await db.exec('RESET ROLE');}});
test('RPC legacy registration and permissions match client protocol',async()=>{
 assert.deepEqual(await store.access(id,'0.1.10',day),{schema:1,allowed:true,reason:''});
 assert.equal(await store.changeAccess(id,false,"  引号'与\\符号  "),true);
 assert.deepEqual(await store.access(id,'0.1.12',day),{schema:1,allowed:false,reason:"引号'与\\符号"});
 assert.equal(await store.changeAccess('b'.repeat(64),false,''),false);await store.changeAccess(id,true,'');
 assert.equal((await store.access(id,'0.1.12',day)).allowed,true);
});
test('RPC repeated, out-of-order and concurrent snapshots use only positive differences',async()=>{
 await Promise.all([op([4,2,1,0]),op([1,1,0,0]),op([4,3,0,1]),op([2,0,0,0])].map(o=>store.ingest(id,'0.1.12',[day],o)));
 const s=await store.summary(day);assert.equal(s.operations.total.started,6);assert.equal(s.operations.total.extract.succeeded,3);assert.equal(s.operations.total.extract.failed,1);assert.equal(s.operations.total.extract.cancelled,1);
});
test('RPC failure after totals write rolls back complete report',async()=>{
 await store.ingest(id,'0.1.12',[day],op([1,1,0,0]));
 await db.exec("CREATE FUNCTION public.suikan_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test fault'; END $$; CREATE TRIGGER suikan_test_fault BEFORE INSERT OR UPDATE ON suikan.operation_daily FOR EACH ROW EXECUTE FUNCTION public.suikan_test_fault();");
 try{await assert.rejects(store.ingest(id,'0.1.12',[day],op([9,8,0,0])));assert.equal((await store.summary(day)).operations.total.extract.started,1);}
 finally{await db.exec('DROP TRIGGER suikan_test_fault ON suikan.operation_daily; DROP FUNCTION public.suikan_test_fault();');}
 await store.ingest(id,'0.1.12',[day],op([9,8,0,0]));assert.equal((await store.summary(day)).operations.total.extract.started,9);
});
test('RPC summary and paginated rows match direct pg implementation',async()=>{
 await store.ingest(id,'0.1.10',['2026-09-10','2026-10-09'],op([1,0,0,0],[0,0,0,0],'2026-09-10'));
 await store.ingest(id,'0.1.12',[day],op());await store.access('b'.repeat(64),'0.1.11',day);await store.changeAccess(id,false,'暂停');
 const pool={connect:async()=>({query:async(sql,args)=>(await db.query(sql,args)),release(){}})};
 const direct=new PostgresStore(pool);
 assert.deepEqual(await store.summary(day),await direct.summary(day));
 for(const q of ['',id.slice(0,8),'f'])assert.deepEqual(await store.installations({q,offset:0,limit:1}),await direct.installations({q,offset:0,limit:1}));
});
test('RPC retention keeps lifetime counts and disabled state',async()=>{
 await store.ingest(id,'0.1.12',['2026-07-01'],op([8,8,0,0],[0,0,0,0],'2026-07-01'));await store.changeAccess(id,false,'保留');await store.pruneIfDue(day);
 assert.equal((await db.query('SELECT count(*) n FROM suikan.operation_daily')).rows[0].n,0);assert.equal((await store.summary(day)).operations.total.started,8);assert.equal((await store.access(id,'0.1.12',day)).allowed,false);
});
test('RPC full Android payload works through unchanged HTTP handler',async()=>{
 const h=createHandler({store,token,clock:()=>Date.parse(day+'T12:00:00+08:00'),limiter:{allow:()=>true}});
 const uuid='0f6cdb36-3e0a-4537-91d6-9ca6a840d341',body={schema:2,installation_id:uuid,app_version:'0.1.12',active_days:[day],operation_days:op()};
 const r=await h(new Request('https://example.invalid/v1/usage',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}));
 assert.equal(r.status,204);assert.equal((await store.installations({q:'',offset:0,limit:10})).items[0].id,installationHash(uuid));
});
test('RPC transport pins environment endpoint and never follows redirects',async()=>{
 let captured;const call=cloudBaseRpc({SUIKAN_ENV_ID:'test-env-123456',CLOUDBASE_APIKEY:'test-key'},async(url,init)=>{captured={url,init};return new Response('{"ok":true}');});
 assert.deepEqual(await call('health',{}),{ok:true});assert.equal(captured.url,'https://test-env-123456.api.tcloudbasegateway.com/v1/rdb/rest/rpc/suikan_statistics_rpc');assert.equal(captured.init.redirect,'error');assert.deepEqual(JSON.parse(captured.init.body),{action:'health',p:{}});
});
test('RPC configuration and unsafe numeric results fail closed',()=>{assert.throws(()=>cloudBaseRpc({SUIKAN_ENV_ID:'https://attacker.invalid',CLOUDBASE_APIKEY:'x'}));assert.throws(()=>assertSafe({n:9007199254740992}));});
test('RPC network/database errors do not return credentials or upstream text',async()=>{
 const call=cloudBaseRpc({SUIKAN_ENV_ID:'test-env-123456',CLOUDBASE_APIKEY:'private-test-key'},async()=>new Response('sensitive upstream body',{status:403}));
 await assert.rejects(call('health',{}),e=>e.message==='CloudBase database temporarily unavailable');
});
