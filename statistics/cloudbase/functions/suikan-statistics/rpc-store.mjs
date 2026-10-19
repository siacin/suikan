export function assertSafe(value){
  if(typeof value==='number'&&!Number.isSafeInteger(value))throw new Error('Invalid database number');
  if(Array.isArray(value))value.forEach(assertSafe);
  else if(value&&typeof value==='object')Object.values(value).forEach(assertSafe);
  return value;
}
export class RpcStore {
  constructor(call,videoCall=call){this.call=async(action,p={})=>assertSafe(await call(action,p));this.videoCall=async(action,p)=>assertSafe(await videoCall(action,p));this.lastPruneDay='';}
  videoIngest(id,version,client,events,today){return this.videoCall('ingest',{id,version,client,events,today});}
  videoList(action,query){return this.videoCall(action,query);}
  health(){return this.call('health');}
  access(id,version,today){return this.call('access',{id,version,today});}
  ingest(id,version,days,operations=[]){if(days.length)return this.call('ingest',{id,version,days,operations});}
  changeAccess(id,enabled,reason){return this.call('changeAccess',{id,enabled,reason});}
  installations(query){return this.call('installations',query);}
  summary(today){return this.call('summary',{today});}
  async pruneIfDue(today){if(this.lastPruneDay!==today){await this.call('prune',{today});this.lastPruneDay=today;}}
}
export function cloudBaseRpc(env=process.env,fetcher=fetch,fn='suikan_statistics_rpc'){
  const id=env.SUIKAN_ENV_ID,key=env.CLOUDBASE_APIKEY;
  if(!/^[a-z0-9][a-z0-9-]{5,127}$/.test(id||'')||!key)throw new Error('CloudBase RPC configuration missing');
  if(!['suikan_statistics_rpc','suikan_video_statistics_rpc'].includes(fn))throw new Error('Invalid RPC');
  const url=`https://${id}.api.tcloudbasegateway.com/v1/rdb/rest/rpc/${fn}`;
  return async(action,p)=>{
    const r=await fetcher(url,{method:'POST',redirect:'error',signal:AbortSignal.timeout(2200),
      headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify({action,p})});
    if(!r.ok)throw new Error('CloudBase database temporarily unavailable');
    const text=await r.text();if(text.length>512000)throw new Error('CloudBase result too large');
    return JSON.parse(text);
  };
}
