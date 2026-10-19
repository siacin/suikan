import {createHash,timingSafeEqual} from 'node:crypto';
export const OUTCOMES=['started','succeeded','failed','cancelled'];
export const COUNTERS=['extract','summary'].flatMap(kind=>OUTCOMES.map(outcome=>kind+'_'+outcome));
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![\s\S])/;
const VERSION=/^[0-9][0-9A-Za-z.+_-]{0,39}(?![\s\S])/;
export const HASH=/^[0-9a-f]{64}(?![\s\S])/;
const CSP="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'";
export class HttpError extends Error {constructor(status,code){super(code);this.status=status;}}
export function reject(status,code){throw new HttpError(status,code);}
export function response(value,status=200,mime='application/json; charset=utf-8'){
  const headers={'Content-Type':mime,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
    'Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','Content-Security-Policy':CSP};
  if(status===429)headers['Retry-After']='60';
  return new Response(status===204?null:(typeof value==='string'?value:JSON.stringify(value)),{status,headers});
}
export const chinaDay=(now=Date.now())=>new Date(now+8*3600000).toISOString().slice(0,10);
export const shiftDay=(day,n)=>new Date(Date.parse(day+'T00:00:00Z')+n*86400000).toISOString().slice(0,10);
export function validDay(day){
  if(typeof day!=='string'||day.length!==10||!/^\d{4}-\d{2}-\d{2}$/.test(day))return false;
  const time=Date.parse(day+'T00:00:00Z');return Number.isFinite(time)&&new Date(time).toISOString().slice(0,10)===day;
}
export const installationHash=id=>createHash('sha256').update('suikan-usage-v1:'+id).digest('hex');
export function exactKeys(value,keys){return value!==null&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).sort().join(',')===[...keys].sort().join(',');}
export function validToken(token){return typeof token==='string'&&token.length>=32&&token.length<=256&&!token.startsWith('replace-')&&!/[\x00-\x20\x7f]/.test(token);}
export function authorized(request,token){
  const supplied=request.headers.get('Authorization')||'';if(Buffer.byteLength(supplied)>512)return false;
  return timingSafeEqual(createHash('sha256').update(supplied).digest(),createHash('sha256').update('Bearer '+token).digest());
}
export async function readJson(request,maxSize=2048){
  if((request.headers.get('Content-Type')||'').split(';')[0].trim().toLowerCase()!=='application/json')reject(415,'content_type');
  const length=request.headers.get('Content-Length');
  if(length!==null&&(!/^\d+$/.test(length)||Number(length)>maxSize||Number(length)<1))reject(413,'body_size');
  if(!request.body)reject(400,'invalid_payload');
  const reader=request.body.getReader(),chunks=[];let size=0;
  try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>maxSize){await reader.cancel();reject(413,'body_size');}chunks.push(value);}}
  finally{reader.releaseLock();}
  try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks,size)));}catch{reject(400,'invalid_payload');}
}
export function validateClient(value,usage){
  const fields=['schema','installation_id','app_version'];if(usage)fields.push('active_days');
  const modern=usage&&value?.schema===2;if(modern)fields.push('operation_days');
  if(!exactKeys(value,fields)||(!modern&&value.schema!==1)||typeof value.installation_id!=='string'||!UUID.test(value.installation_id)||typeof value.app_version!=='string'||!VERSION.test(value.app_version))reject(400,'invalid_payload');
  if(usage&&(!Array.isArray(value.active_days)||value.active_days.length<(modern?0:1)||value.active_days.length>31||!value.active_days.every(validDay)))reject(400,'invalid_payload');
  if(modern){
    if(!Array.isArray(value.operation_days)||value.operation_days.length>31||(!value.operation_days.length&&!value.active_days.length))reject(400,'invalid_payload');
    const seen=new Set();for(const r of value.operation_days){
      if(!exactKeys(r,['day','extract','summary'])||!validDay(r.day)||seen.has(r.day))reject(400,'invalid_payload');seen.add(r.day);
      for(const kind of ['extract','summary'])if(!Array.isArray(r[kind])||r[kind].length!==4||!r[kind].every(n=>Number.isInteger(n)&&n>=0&&n<=1000000000))reject(400,'invalid_payload');
    }
  }
}
export function metrics(row={}){
  const result={extract:{},summary:{}};
  for(const outcome of OUTCOMES){for(const kind of ['extract','summary']){
    const n=Number(row[kind+'_'+outcome]||0);if(!Number.isSafeInteger(n)||n<0)throw new Error('Invalid aggregate');result[kind][outcome]=n;
  }result[outcome]=result.extract[outcome]+result.summary[outcome];if(!Number.isSafeInteger(result[outcome]))throw new Error('Aggregate overflow');}
  return result;
}
// Limits are per warm instance. Cloud gateway limits may be added for an account-wide ceiling.
export class RateLimit {
  constructor(){this.buckets=new Map();}
  allow(category,key,limit,now=Date.now()){
    const minute=Math.floor(now/60000),id=category+':'+createHash('sha256').update(key).digest('hex');
    const old=this.buckets.get(id),count=old?.minute===minute?old.count+1:1;
    this.buckets.delete(id);this.buckets.set(id,{minute,count});
    while(this.buckets.size>10000)this.buckets.delete(this.buckets.keys().next().value);
    return count<=limit;
  }
}
