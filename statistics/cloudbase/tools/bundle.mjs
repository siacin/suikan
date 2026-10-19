import {mkdir,readFile,writeFile,copyFile,readdir,chmod} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import '../prepare-assets.mjs';
const root=fileURLToPath(new URL('../',import.meta.url)),target=path.join(root,'.local','upload','suikan-statistics');
await mkdir(target,{recursive:true});
const source=path.join(root,'functions','suikan-statistics');
for(const name of await readdir(source)){
  if(!['package.json','scf_bootstrap'].includes(name)&&!name.endsWith('.mjs')&&name!=='index.js')continue;
  const bytes=await readFile(path.join(source,name));
  if(name==='scf_bootstrap'){
    await writeFile(path.join(target,name),bytes.toString('utf8').replaceAll('\r\n','\n'));await chmod(path.join(target,name),0o755);
  }else await copyFile(path.join(source,name),path.join(target,name));
}
// Lock the production dependency tree; root dev dependencies are not bundled.
const lock=JSON.parse(await readFile(path.join(root,'package-lock.json'),'utf8'));
lock.name='suikan-statistics';lock.packages[''].name='suikan-statistics';delete lock.packages[''].devDependencies;
for(const [name,item] of Object.entries(lock.packages))if(item.dev)delete lock.packages[name];
await writeFile(path.join(target,'package-lock.json'),JSON.stringify(lock,null,2)+'\n');
console.log('Upload source prepared: .local/upload/suikan-statistics');
