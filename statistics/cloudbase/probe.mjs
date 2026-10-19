import {readFile} from 'node:fs/promises';
const base=new URL(process.argv[2]||'');
if(base.protocol!=='https:'||base.username||base.password||base.pathname!=='/'||base.search||base.hash)throw new Error('Expected HTTPS backend root URL');
const tokenFile=process.argv[3],token=tokenFile?(await readFile(tokenFile,'utf8')).trim():'';
const paths=token?['/healthz','/','/dashboard.js','/api/summary','/api/installations?limit=1']:['/healthz'];
for(const path of paths){
  const r=await fetch(new URL(path,base),{redirect:'error',signal:AbortSignal.timeout(20000),headers:path.startsWith('/api/')?{Authorization:'Bearer '+token}:{}});
  if(r.status!==200)throw new Error('Probe failed: '+path+' HTTP '+r.status);
  if(path==='/healthz'&&(await r.json()).ok!==true)throw new Error('Invalid health response');
  else if(path.startsWith('/api/')){const value=await r.json();if(path.startsWith('/api/summary')&&!Number.isInteger(value.total_installations))throw new Error('Invalid summary response');if(path.startsWith('/api/installations')&&!Array.isArray(value.items))throw new Error('Invalid installations response');}
  console.log('PASS '+path);
}
console.log('Read-only probe complete; no test installations created.');
