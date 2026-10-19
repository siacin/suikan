import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {validToken} from '../functions/suikan-statistics/protocol.mjs';
const dir=new URL('../.local/',import.meta.url),file=new URL('admin-token.txt',dir);
await mkdir(dir,{recursive:true});
try{const current=(await readFile(file,'utf8')).trim();if(!validToken(current))throw new Error('Invalid saved management token');console.log('Existing local management token preserved.');}
catch(error){if(error.code!=='ENOENT')throw error;await writeFile(file,randomBytes(24).toString('hex')+'\n',{flag:'wx',mode:0o600});console.log('Management token saved to .local/admin-token.txt; not printed.');}
