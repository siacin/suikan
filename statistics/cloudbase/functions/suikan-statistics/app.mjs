import assets from './assets.mjs';
import {validateVideo,videoQuery} from './video-protocol.mjs';
import {HttpError,HASH,RateLimit,authorized,chinaDay,shiftDay,exactKeys,installationHash,readJson,reject,response,validToken,validateClient} from './protocol.mjs';
function listQuery(url){
  if(url.search.length>257)reject(400,'invalid_query');
  const p=url.searchParams;for(const key of p.keys())if(!['offset','limit','q'].includes(key)||p.getAll(key).length!==1)reject(400,'invalid_query');
  const a=p.get('offset')??'0',b=p.get('limit')??'50',q=p.get('q')??'',offset=Number(a),limit=Number(b);
  if(!/^\d+(?![\s\S])/.test(a)||!/^\d+(?![\s\S])/.test(b)||offset>1000000000||limit<1||limit>100||!/^[0-9a-f]{0,64}(?![\s\S])/.test(q))reject(400,'invalid_query');
  return {offset,limit,q};
}
export function createHandler({store,token,clock=Date.now,limiter=new RateLimit()}){
  const allow=(kind,key,max,now)=>{if(!limiter.allow(kind,key,max,now))reject(429,'rate_limited');};
  return async function handle(request,remoteAddress='local'){
    try{
      const url=new URL(request.url),path=url.pathname,method=request.method,now=clock(),today=chinaDay(now);
      if(Object.hasOwn(assets,path)&&method==='GET')return response(assets[path].body,200,assets[path].mime);
      if(path==='/healthz'){
        if(method!=='GET')reject(405,'method_not_allowed');
        if(!store||!validToken(token))reject(503,'not_configured');await store.health();return response({ok:true});
      }
      const video=path==='/v1/video-usage',usage=path==='/v1/usage',client=video||usage||path==='/v1/access',admin=['/api/summary','/api/installations','/api/access','/api/videos','/api/video-events'].includes(path);
      if(!client&&!admin)reject(404,'not_found');if(!store||!validToken(token))reject(503,'not_configured');
      if(client){
        if(method!=='POST')reject(405,'method_not_allowed');allow('public','all',6000,now);
        const body=await readJson(request,video?32768:usage?8192:2048);if(video)validateVideo(body,now);else validateClient(body,usage);
        const id=installationHash(body.installation_id);allow(video?'video-client':'client',id,video?60:12,now);
        if(video){await store.videoIngest(id,body.app_version,body.client,body.events,today);return response(null,204);}
        if(!usage)return response(await store.access(id,body.app_version,today));
        const oldest=shiftDay(today,-30),operations=(body.operation_days||[]).filter(r=>r.day>=oldest&&r.day<=today);
        const days=[...body.active_days,...operations.map(r=>r.day)].filter(day=>day>=oldest&&day<=today);
        if(days.length){await store.pruneIfDue(today);await store.ingest(id,body.app_version,days,operations);}
        return response(null,204);
      }
      allow('admin',remoteAddress,60,now);if(!authorized(request,token))reject(401,'unauthorized');
      if(path==='/api/summary'&&method==='GET')return response(await store.summary(today));
      if(path==='/api/installations'&&method==='GET')return response(await store.installations(listQuery(url)));
      if((path==='/api/videos'||path==='/api/video-events')&&method==='GET')return response(await store.videoList(path==='/api/videos'?'videos':'events',videoQuery(url,today)));
      if(path==='/api/access'&&method==='POST'){
        const body=await readJson(request);
        if(!exactKeys(body,['id','enabled','reason'])||typeof body.id!=='string'||!HASH.test(body.id)||typeof body.enabled!=='boolean'||typeof body.reason!=='string'||body.reason.length>200||/[\x00-\x1f]/.test(body.reason))reject(400,'invalid_payload');
        if(!await store.changeAccess(body.id,body.enabled,body.reason))reject(404,'installation_not_found');return response({ok:true});
      }
      reject(405,'method_not_allowed');
    }catch(error){return response({error:error instanceof HttpError?error.message:'temporarily_unavailable'},error instanceof HttpError?error.status:503);}
  };
}
