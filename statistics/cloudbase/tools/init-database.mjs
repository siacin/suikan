import {readFile} from 'node:fs/promises';
import {createPool} from '../functions/suikan-statistics/config.mjs';
let pool;
try{pool=createPool();for(const name of ['0001_schema.sql','0003_video_usage.sql'])await pool.query(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8'));console.log('PostgreSQL schema ready.');}
catch{console.error('Initialization failed. Execute migrations/0001_schema.sql, 0002_rpc.sql, 0003_video_usage.sql in order using the administrator SQL console.');process.exitCode=1;}
finally{await pool?.end();}
