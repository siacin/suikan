import {test,before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {PGlite} from '@electric-sql/pglite';
import {TABLES,importSnapshot,validateSnapshot} from '../tools/import-snapshot.mjs';
import {PostgresStore} from '../functions/suikan-statistics/store.mjs';
import {COUNTERS,installationHash} from '../functions/suikan-statistics/protocol.mjs';
let db,pool;const id=installationHash('0f6cdb36-3e0a-4537-91d6-9ca6a840d341');
function snapshot(){
  const numbers=Object.fromEntries(COUNTERS.map((c,i)=>[c,[5,3,1,1,4,2,1,1][i]]));
  return {schema:1,tables:{installations:[{id,first_day:'2026-09-01',last_day:'2026-10-10',app_version:'0.1.11'}],active:[{day:'2026-10-10',id}],
    installation_access:[{id,enabled:0,reason:'保留停用状态'}],operation_daily:[{day:'2026-10-10',id,...numbers}],operation_totals:[{id,...numbers,extract_started:10}]}};
}
before(async()=>{
  db=new PGlite();await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));
  const query=async(sql,args=[])=>{const r=await db.query(sql,args);return {...r,rowCount:r.affectedRows??r.rows.length};};
  pool={query,connect:async()=>({query,release:()=>{}})};
});
beforeEach(async()=>{await db.exec('TRUNCATE '+Object.keys(TABLES).map(t=>'suikan.'+t).join(',')+' CASCADE');});
after(async()=>{await db?.close();});
test('imports original identifiers, dates, lifetime counts and disabled access atomically',async()=>{
  assert.deepEqual(await importSnapshot(pool,snapshot()),Object.fromEntries(Object.keys(TABLES).map(t=>[t,1])));
  const store=new PostgresStore(pool),s=await store.summary('2026-10-10');assert.equal(s.total_installations,1);assert.equal(s.operations.total.started,14);assert.equal(s.operations.today.started,9);
  assert.deepEqual(await store.access(id,'0.1.12','2026-10-10'),{schema:1,allowed:false,reason:'保留停用状态'});
  await store.ingest(id,'0.1.12',['2026-10-10'],[{day:'2026-10-10',extract:[6,4,1,1],summary:[4,2,1,1]}]);
  assert.equal((await store.summary('2026-10-10')).operations.total.started,15);
});
test('refuses a second import and leaves the existing statistics untouched',async()=>{
  await importSnapshot(pool,snapshot());await assert.rejects(importSnapshot(pool,snapshot()),/Destination is not empty/);
  assert.equal((await new PostgresStore(pool).summary('2026-10-10')).operations.total.started,14);
});
test('rejects orphan, duplicate, malformed and incomplete records before database writes',()=>{
  const cases=[];const mutate=fn=>{const v=snapshot();fn(v);cases.push(v);};
  mutate(v=>v.tables.active[0].id='a'.repeat(64));mutate(v=>v.tables.active.push(v.tables.active[0]));
  mutate(v=>v.tables.installations[0].id='uuid-is-not-a-hash');mutate(v=>v.tables.installations[0].last_day='2026-02-30');
  mutate(v=>v.tables.operation_totals[0].extract_started=1);mutate(v=>v.tables.operation_daily[0].extract_started=-1);
  mutate(v=>v.tables.operation_totals[0].extract_started=9007199254740992);mutate(v=>v.tables.installation_access[0].enabled=true);
  mutate(v=>v.tables.installations[0].api_key='private');mutate(v=>delete v.tables.operation_totals);
  for(const v of cases)assert.throws(()=>validateSnapshot(v));
});
test('failed insert rolls back all earlier rows',async()=>{
  const query=pool.query;let failed=false;
  const broken={connect:async()=>({query:async(sql,args)=>{if(!failed&&sql.startsWith('INSERT INTO suikan.operation_totals')){failed=true;throw new Error('injected');}return query(sql,args);},release:()=>{}})};
  await assert.rejects(importSnapshot(broken,snapshot()),/injected/);
  for(const t of Object.keys(TABLES))assert.equal(Number((await query(`SELECT count(*) AS n FROM suikan.${t}`)).rows[0].n),0);
});
test('accepts older snapshots with no operation counters without inventing history',async()=>{
  const v=snapshot();v.tables.operation_daily=[];v.tables.operation_totals=[];await importSnapshot(pool,v);
  assert.equal((await new PostgresStore(pool).summary('2026-10-10')).operations.total.started,0);
});
test('production bundle uses the same dashboard and has an LF executable bootstrap',async()=>{
  const source=await import('../functions/suikan-statistics/assets.mjs');
  assert.equal(source.default['/'].body,await readFile(new URL('../../dashboard.html',import.meta.url),'utf8'));
  assert.equal(source.default['/dashboard.js'].body,await readFile(new URL('../../dashboard.js',import.meta.url),'utf8'));
  assert.deepEqual(Object.keys(source.default).sort(),['/','/dashboard.css','/dashboard.js','/favicon.svg']);
  const startup=await readFile(new URL('../functions/suikan-statistics/scf_bootstrap',import.meta.url),'utf8');assert.ok(startup.startsWith('#!/bin/bash\n'));assert.ok(!startup.includes('\r'));assert.match(startup,/\/var\/lang\/node20\/bin\/node/);
});
