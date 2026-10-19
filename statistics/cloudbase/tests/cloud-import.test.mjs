import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PGlite} from '@electric-sql/pglite';
import {readFile} from 'node:fs/promises';
import {importSql,cloudSql} from '../tools/import-cloudbase.mjs';
const id='c'.repeat(64),data=()=>({schema:1,tables:{installations:[{id,first_day:'2026-10-09',last_day:'2026-10-10',app_version:'0.1.11'}],active:[{id,day:'2026-10-10'}],installation_access:[{id,enabled:0,reason:"保留引号'和\\字符"}],operation_daily:[],operation_totals:[]}});
test('native import preserves identifiers and reasons; repeat refuses without changes',async()=>{
 const db=new PGlite();try{
 await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));await db.exec(importSql(data()));
 assert.equal((await db.query('SELECT reason FROM suikan.installation_access')).rows[0].reason,data().tables.installation_access[0].reason);
 await assert.rejects(db.exec(importSql(data())));assert.equal((await db.query('SELECT count(*) n FROM suikan.installations')).rows[0].n,1);
 }finally{await db.close();}
});
test('native import failure rolls back previously inserted tables',async()=>{
 const db=new PGlite();try{
 await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));await db.exec("ALTER TABLE suikan.installation_access ADD CHECK(reason='impossible')");
 await assert.rejects(db.exec(importSql(data())));assert.equal((await db.query('SELECT count(*) n FROM suikan.installations')).rows[0].n,0);
 }finally{await db.close();}
});
test('native import rejects invalid snapshots before network request',()=>{const v=data();v.tables.active[0].id='d'.repeat(64);assert.throws(()=>importSql(v));});
test('native SQL transport pins host, disallows redirects and masks errors',async()=>{
 let request;const env={SUIKAN_ENV_ID:'test-env-123456',CLOUDBASE_APIKEY:'private-test-key'};
 await cloudSql(env,'SELECT 1',async(url,init)=>{request={url,init};return new Response('[]');});assert.equal(request.url,'https://test-env-123456.api.tcloudbasegateway.com/v1/rdb/exec-pgsql');assert.equal(request.init.redirect,'error');
 await assert.rejects(cloudSql(env,'SELECT 1',async()=>new Response('{"code":"DATABASE_42501","message":"private upstream"}',{status:400})),e=>e.message==='CloudBase SQL failed: 400 DATABASE_42501');
});
