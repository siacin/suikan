import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {COUNTERS,HASH,exactKeys,validDay} from '../functions/suikan-statistics/protocol.mjs';
import {createPool} from '../functions/suikan-statistics/config.mjs';
export const TABLES={installations:['id','first_day','last_day','app_version'],active:['day','id'],
  installation_access:['id','enabled','reason'],operation_daily:['day','id',...COUNTERS],operation_totals:['id',...COUNTERS]};
export function validateSnapshot(value){
  if(!exactKeys(value,['schema','tables'])||value.schema!==1||!exactKeys(value.tables,Object.keys(TABLES)))throw new Error('Invalid snapshot schema');
  const ids=new Set(),seen={},daily=new Map(),totals=new Map();
  for(const [table,fields] of Object.entries(TABLES)){
    const rows=value.tables[table];if(!Array.isArray(rows)||rows.length>1000000)throw new Error('Invalid snapshot table');seen[table]=new Set();
    for(const row of rows){
      if(!exactKeys(row,fields)||typeof row.id!=='string'||!HASH.test(row.id))throw new Error('Invalid snapshot record');
      const key=(row.day||'')+':'+row.id;if(seen[table].has(key))throw new Error('Duplicate snapshot record');seen[table].add(key);
      if(table==='installations'){
        if(!validDay(row.first_day)||!validDay(row.last_day)||row.first_day>row.last_day||typeof row.app_version!=='string'||!/^[0-9][0-9A-Za-z.+_-]{0,39}(?![\s\S])/.test(row.app_version))throw new Error('Invalid installation');ids.add(row.id);
      }else if(!ids.has(row.id))throw new Error('Orphan snapshot record');
      if(fields.includes('day')&&!validDay(row.day))throw new Error('Invalid snapshot day');
      if(table==='installation_access'&&(![0,1].includes(row.enabled)||typeof row.reason!=='string'||row.reason.length>200||/[\x00-\x1f]/.test(row.reason)))throw new Error('Invalid access record');
      if(table.startsWith('operation_')){
        if(!COUNTERS.every(c=>Number.isSafeInteger(row[c])&&row[c]>=0))throw new Error('Invalid snapshot counter');
        if(table==='operation_totals')totals.set(row.id,row);
        else{const sum=daily.get(row.id)||Array(8).fill(0);COUNTERS.forEach((c,i)=>{sum[i]+=row[c];if(!Number.isSafeInteger(sum[i]))throw new Error('Snapshot counter overflow');});daily.set(row.id,sum);}
      }
    }
  }
  for(const [id,values] of daily){const row=totals.get(id);if(!row||values.some((n,i)=>n>row[COUNTERS[i]]))throw new Error('Lifetime counters smaller than daily counters');}
  return value.tables;
}
export async function importSnapshot(pool,value){
  const tables=validateSnapshot(value),client=await pool.connect();
  try{
    await client.query('BEGIN');await client.query("SET LOCAL lock_timeout='5000ms'");
    await client.query('LOCK TABLE '+Object.keys(TABLES).map(t=>'suikan.'+t).join(',')+' IN ACCESS EXCLUSIVE MODE');
    for(const table of Object.keys(TABLES))if(Number((await client.query(`SELECT count(*) AS n FROM suikan.${table}`)).rows[0].n)!==0)throw new Error('Destination is not empty; import stopped without changes');
    for(const [table,fields] of Object.entries(TABLES))for(const row of tables[table])await client.query(`INSERT INTO suikan.${table}(${fields.join(',')}) VALUES(${fields.map((_,i)=>'$'+(i+1)).join(',')})`,fields.map(c=>row[c]));
    await client.query('COMMIT');return Object.fromEntries(Object.entries(tables).map(([t,rows])=>[t,rows.length]));
  }catch(error){try{await client.query('ROLLBACK');}catch{}throw error;}finally{client.release();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  let pool;
  try{
    const raw=await readFile(process.argv[2]);if(raw.length>64*1024*1024)throw new Error('Snapshot too large');
    pool=createPool();const counts=await importSnapshot(pool,JSON.parse(raw));console.log('Import committed; row counts: '+JSON.stringify(counts));
  }catch(error){console.error(error.message==='Destination is not empty; import stopped without changes'?error.message:'Import failed; verify the private configuration and snapshot. No credentials printed.');process.exitCode=1;}
  finally{await pool?.end();}
}
