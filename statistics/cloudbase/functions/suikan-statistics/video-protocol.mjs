import {exactKeys,reject,validDay,shiftDay,chinaDay} from './protocol.mjs';
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![\s\S])/;
const bv=/^BV[0-9A-Za-z]{10}(?![\s\S])/;
const fields=['event_id','started_at','completed_at','status','depth','cached','bvid','aid','cid','page','title'];
export function validateVideo(body,now){
  if(!exactKeys(body,['schema','installation_id','app_version','client','events'])||body.schema!==1||typeof body.installation_id!=='string'||!uuid.test(body.installation_id)||typeof body.app_version!=='string'||!/^\d[0-9A-Za-z.+_-]{0,39}(?![\s\S])/.test(body.app_version)||!['android','browser'].includes(body.client)||!Array.isArray(body.events)||body.events.length<1||body.events.length>20)reject(400,'invalid_payload');
  const ids=new Set(),oldest=Date.parse(shiftDay(chinaDay(now),-30)+'T00:00:00+08:00');
  for(const e of body.events){
    if(!exactKeys(e,fields)||typeof e.event_id!=='string'||!uuid.test(e.event_id)||ids.has(e.event_id)||typeof e.bvid!=='string'||!bv.test(e.bvid)||typeof e.title!=='string'||!e.title.trim()||e.title.length>256||/[\x00-\x1f\x7f]/.test(e.title)||!['concise','standard','detailed'].includes(e.depth)||!['started','succeeded','failed','cancelled'].includes(e.status)||typeof e.cached!=='boolean'||(e.cached&&e.status!=='succeeded')||!['started_at','completed_at','aid','cid','page'].every(k=>Number.isSafeInteger(e[k]))||e.aid<1||e.cid<1||e.page<1||e.page>10000||e.started_at<oldest||e.started_at>now+300000||e.completed_at<0||e.completed_at>now+300000||(e.status==='started'?e.completed_at!==0:e.completed_at<e.started_at))reject(400,'invalid_event');
    ids.add(e.event_id);
  }
  return body;
}
export function videoQuery(url,today){
  if(url.search.length>700)reject(400,'invalid_query');
  const p=url.searchParams;for(const k of p.keys())if(!['from','to','q','bvid','client','offset','limit'].includes(k)||p.getAll(k).length!==1)reject(400,'invalid_query');
  const from=p.get('from')||shiftDay(today,-29),to=p.get('to')||today,q=p.get('q')||'',bvid=p.get('bvid')||'',client=p.get('client')||'',a=p.get('offset')??'0',b=p.get('limit')??'50',offset=Number(a),limit=Number(b);
  if(!validDay(from)||!validDay(to)||from>to||from<shiftDay(today,-89)||to>today||!(/^[0-9a-f]{0,64}$/.test(q))||(bvid&&!bv.test(bvid))||(client&&!['android','browser'].includes(client))||!/^\d+$/.test(a)||!/^\d+$/.test(b)||offset>1e9||limit<1||limit>100)reject(400,'invalid_query');
  return {from,to,q,bvid,client,offset,limit};
}
