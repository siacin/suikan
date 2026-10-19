import {readFile,writeFile,mkdir,copyFile,access} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)),privateDir=path.join(root,'.local');
const [id,entry]=process.argv.slice(2),exists=async p=>{try{await access(p);return true;}catch{return false;}};
function cli(args){
 const r=spawnSync(process.execPath,[entry,...args,'--json'],{encoding:'utf8',timeout:60000,windowsHide:true,maxBuffer:2e6});
 const start=r.stdout?.search(/[\[{]/),end=Math.max(r.stdout?.lastIndexOf('}')??-1,r.stdout?.lastIndexOf(']')??-1);
 let v;try{v=JSON.parse(r.stdout.slice(start,end+1));}catch{throw new Error('Official CLI returned no structured result; verify login/network');}
 if(r.status||v.error)throw new Error('Official CLI failed: '+(v.error?.code||'unavailable'));
 return v.data||v;
}
try{
 if(!/^[a-z0-9][a-z0-9-]{5,127}$/.test(id||'')||!entry||!await exists(entry))throw new Error('Usage: node tools/prepare-native.mjs <real-env-id> <official-cli.js>');
 await mkdir(privateDir,{recursive:true});const runtimePath=path.join(privateDir,'runtime.env.json');
 if(await exists(runtimePath))throw new Error('Private runtime configuration already exists; preserve it and manage changes locally');
 for(const name of ['0001_schema.sql','0002_rpc.sql','0003_video_usage.sql']){
  cli(['db','execute','-e',id,'--region','ap-shanghai','--sql',await readFile(path.join(root,'migrations',name),'utf8')]);
  console.log('Applied '+name);
 }
 const keyPath=path.join(privateDir,'server-api-key.json');let key;
 if(await exists(keyPath))key=JSON.parse(await readFile(keyPath,'utf8'));
 else{key=cli(['env','apikey','create','suikan-statistics-server','-e',id,'--region','ap-shanghai']);await writeFile(keyPath,JSON.stringify(key),{flag:'wx',mode:0o600});}
 if(!key.ApiKey)throw new Error('Environment API key missing');
 const tokenPath=path.join(privateDir,'admin-token.txt'),oldToken=path.resolve(root,'../cloudflare/.local/admin-token.txt');
 if(!await exists(tokenPath)){
  if(await exists(oldToken))await copyFile(oldToken,tokenPath);
  else await writeFile(tokenPath,randomBytes(24).toString('hex')+'\n',{flag:'wx',mode:0o600});
 }
 const token=(await readFile(tokenPath,'utf8')).trim();
 if(token.length<32||token.length>256||/[\x00-\x20\x7f]/.test(token)||token.startsWith('replace-'))throw new Error('Invalid private admin token');
 await writeFile(runtimePath,JSON.stringify({SUIKAN_DB_MODE:'cloudbase-rpc',SUIKAN_ENV_ID:id,CLOUDBASE_APIKEY:key.ApiKey,SUIKAN_ADMIN_TOKEN:token},null,2)+'\n',{flag:'wx',mode:0o600});
 console.log('Private configuration saved. No credentials printed; no package purchased or upgraded.');
}catch(e){console.error(e.message);process.exitCode=1;}
