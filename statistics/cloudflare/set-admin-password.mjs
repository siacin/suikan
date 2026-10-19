// Run after the initial deployment. Never put the administration token into the APK.
import { randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('.',import.meta.url)),privateDir=new URL('.local/',import.meta.url);
await readFile(new URL('wrangler.json',import.meta.url));
await mkdir(privateDir,{recursive:true});
const active=new URL('admin-token.txt',privateDir),pending=new URL('admin-token.pending.txt',privateDir);
let token;
try { token=(await readFile(active,'utf8')).trim(); }
catch { try {token=(await readFile(pending,'utf8')).trim();} catch {token=randomBytes(36).toString('base64url');} }
if(!/^[A-Za-z0-9_-]{48}$/.test(token))throw new Error('本地管理口令格式异常，请先检查 .local 中的口令文件。');
await writeFile(pending,token+'\n',{mode:0o600});
const result=spawnSync(process.execPath,[fileURLToPath(new URL('node_modules/wrangler/bin/wrangler.js',import.meta.url)),
  'secret','put','SUIKAN_ADMIN_TOKEN','--config','wrangler.json'],
  {cwd:root,input:token+'\n',stdio:['pipe','inherit','inherit'],env:{...process.env,WRANGLER_SEND_METRICS:'false'}});
if(result.error||result.status!==0){process.exitCode=1;console.error('上传未成功；原口令文件保留，新口令在 .local/admin-token.pending.txt，可原样重试。');}
else {await rename(pending,active);console.log('口令已设置。用记事本打开 .local/admin-token.txt 查看；不要发给 APP 用户或上传 GitHub。');}
