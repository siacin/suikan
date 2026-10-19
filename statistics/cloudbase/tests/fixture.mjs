import {PGlite} from '@electric-sql/pglite';
import {readFile} from 'node:fs/promises';
import {PostgresStore} from '../functions/suikan-statistics/store.mjs';
import {createHandler} from '../functions/suikan-statistics/app.mjs';
import {createServer} from '../functions/suikan-statistics/http.mjs';
import {installationHash} from '../functions/suikan-statistics/protocol.mjs';
const db=new PGlite();await db.exec(await readFile(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));
let tail=Promise.resolve();
const pool={async connect(){const previous=tail;let release;tail=new Promise(r=>release=r);await previous;return {query:async(sql,args=[])=>{const r=await db.query(sql,args);return {...r,rowCount:r.affectedRows??r.rows.length};},release};},async query(sql,args=[]){const c=await this.connect();try{return await c.query(sql,args);}finally{c.release();}}};
const store=new PostgresStore(pool),today='2026-10-10';
for(let i=0;i<184;i++){
  const id=installationHash('cloudbase-local-dashboard-fixture-'+i),version=i%2?'0.1.12':'0.1.11';
  await store.ingest(id,version,[today],[{day:today,extract:[i%5+1,i%5,0,0],summary:[i%3+1,i%3,1,0]}]);
}
const token='local-test-statistics-'+'x'.repeat(32);
const handler=createHandler({store,token,clock:()=>Date.parse(today+'T12:00:00+08:00'),limiter:{allow:()=>true}});
const server=createServer(handler);server.listen(18772,'127.0.0.1',()=>console.log('CloudBase local PostgreSQL dashboard fixture ready on 127.0.0.1:18772; example data only.'));
process.once('SIGTERM',()=>server.close(()=>db.close().finally(()=>process.exit(0))));
