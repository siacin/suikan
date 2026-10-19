import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {TABLES,validateSnapshot} from './import-snapshot.mjs';
export function importSql(value){
 const tables=validateSnapshot(value),names=Object.keys(TABLES);
 let sql='DO $suikan_import$ BEGIN\nLOCK TABLE '+names.map(t=>'suikan.'+t).join(',')+' IN ACCESS EXCLUSIVE MODE;\n';
 sql+='IF '+names.map(t=>`EXISTS(SELECT 1 FROM suikan.${t})`).join(' OR ')+" THEN RAISE EXCEPTION 'Destination is not empty; import refused'; END IF;\n";
 for(const [table,fields] of Object.entries(TABLES)){
  const hex=Buffer.from(JSON.stringify(tables[table]),'utf8').toString('hex');
  sql+=`INSERT INTO suikan.${table}(${fields.join(',')}) SELECT ${fields.join(',')} FROM jsonb_populate_recordset(NULL::suikan.${table},convert_from(decode('${hex}','hex'),'UTF8')::jsonb);\n`;
 }
 sql+='END $suikan_import$;';
 if(Buffer.byteLength(sql)>1048576)throw new Error('Snapshot requires a larger offline migration; no data imported');
 return sql;
}
export async function cloudSql(env,sql,fetcher=fetch){
 if(!/^[a-z0-9][a-z0-9-]{5,127}$/.test(env.SUIKAN_ENV_ID||'')||!env.CLOUDBASE_APIKEY)throw new Error('Private environment configuration missing');
 const r=await fetcher(`https://${env.SUIKAN_ENV_ID}.api.tcloudbasegateway.com/v1/rdb/exec-pgsql`,{
  method:'POST',redirect:'error',signal:AbortSignal.timeout(60000),headers:{'Content-Type':'application/json',Authorization:'Bearer '+env.CLOUDBASE_APIKEY},
  body:JSON.stringify({sql,role:'cloudbase_postgres'})});
 if(!r.ok){let code;try{code=(await r.json()).code;}catch{}throw new Error('CloudBase SQL failed: '+r.status+' '+(/^[A-Z0-9_]+$/.test(code||'')?code:'unavailable'));}
 return r.json();
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 try{
  const raw=await readFile(process.argv[2]);if(raw.length>64*1024*1024)throw new Error('Snapshot too large');
  const value=JSON.parse(raw),env=JSON.parse(await readFile(new URL('../.local/runtime.env.json',import.meta.url),'utf8'));
  await cloudSql(env,importSql(value));console.log('Import committed: '+JSON.stringify(Object.fromEntries(Object.entries(value.tables).map(([t,r])=>[t,r.length]))));
 }catch(e){console.error(e.message);process.exitCode=1;}
}
