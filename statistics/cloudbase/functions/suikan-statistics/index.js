import {createPool} from './config.mjs';
import {PostgresStore} from './store.mjs';
import {createHandler} from './app.mjs';
import {createServer} from './http.mjs';
import {RpcStore,cloudBaseRpc} from './rpc-store.mjs';
let pool,store;
try{
  if(process.env.SUIKAN_DB_MODE==='cloudbase-rpc')store=new RpcStore(cloudBaseRpc(),cloudBaseRpc(process.env,fetch,'suikan_video_statistics_rpc'));
  else{pool=createPool();store=new PostgresStore(pool);}
}catch{console.error('Statistics database is not configured');}
const server=createServer(createHandler({store,token:process.env.SUIKAN_ADMIN_TOKEN}));
server.listen(9000,'0.0.0.0',()=>console.log('SuiKan statistics HTTP service ready'));
process.once('SIGTERM',()=>server.close(()=>{pool?.end().finally(()=>process.exit(0));if(!pool)process.exit(0);}));
