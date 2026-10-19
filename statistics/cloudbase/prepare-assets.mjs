import {readFile,writeFile} from 'node:fs/promises';
const types={'dashboard.html':'text/html; charset=utf-8','dashboard.js':'text/javascript; charset=utf-8',
  'dashboard.css':'text/css; charset=utf-8','favicon.svg':'image/svg+xml'};
const assets={};
for(const [file,mime] of Object.entries(types))assets[file==='dashboard.html'?'/':'/'+file]={body:await readFile(new URL('../'+file,import.meta.url),'utf8'),mime};
await writeFile(new URL('functions/suikan-statistics/assets.mjs',import.meta.url),'// Generated from the shared statistics dashboard.\nexport default '+JSON.stringify(assets)+';\n');
