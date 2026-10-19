// Only explicit, verified-video summary tasks enter this bounded queue.
const retryMs=15*60*1000;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function descriptor(source,depth){
  if(source?.sourceKind!=='bili'||source.sourceCheckVersion!==1||!['concise','standard','detailed'].includes(depth)||typeof source.bvid!=='string'||!/^BV[0-9A-Za-z]{10}$/.test(source.bvid)||!['aid','cid','page'].every(k=>Number.isSafeInteger(source[k])&&source[k]>0)||source.page>10000||typeof source.title!=='string')return null;
  const title=source.title.replace(/[\s\x00-\x1f\x7f]+/g,' ').trim().slice(0,256);if(!title)return null;
  return {bvid:source.bvid,aid:source.aid,cid:source.cid,page:source.page,title,depth};
}
export class VideoAnalytics {
  constructor({load,save,post,version,now=Date.now,random=()=>crypto.randomUUID()}){Object.assign(this,{load,save,post,version,now,random});this.serial=Promise.resolve();this.sending=false;this.state=null;}
  edit(work){const result=this.serial.then(async()=>{
    if(!this.state){const saved=await this.load();this.state=saved&&uuid.test(saved.id)?saved:{id:this.random(),active:{},pending:{},retryAt:0,ackDay:''};}
    const s=this.state;s.active||={};s.pending||={};if(s.retryAt>this.now()+retryMs)s.retryAt=0;
    const oldest=Math.floor((this.now()+8*3600000)/86400000)*86400000-8*3600000-30*86400000;
    for(const map of [s.active,s.pending])for(const [id,e] of Object.entries(map))if(!e||e.started_at<oldest||e.started_at>this.now()+300000)delete map[id];
    const value=await work(s);await this.save(s);return value;
  });this.serial=result.catch(()=>{});return result;}
  start(source,depth){const d=descriptor(source,depth);if(!d)return Promise.resolve('');return this.edit(s=>{
    if(new Set([...Object.keys(s.pending),...Object.keys(s.active)]).size>=1000)return '';
    const event_id=this.random(),e={...d,event_id,started_at:this.now(),completed_at:0,status:'started',cached:false};s.active[event_id]=e;s.pending[event_id]=e;return event_id;
  });}
  finish(id,status){if(!['succeeded','failed','cancelled'].includes(status))return Promise.resolve();return this.edit(s=>{const e=s.active[id];if(e){s.pending[id]={...e,status,completed_at:Math.max(e.started_at,this.now())};delete s.active[id];}});}
  async foreground(){await this.edit(()=>{});await this.flush(true);}
  async flush(register=false){
    if(this.sending)return;this.sending=true;
    try{
      for(let batch=0;batch<3;batch++){
        const snapshot=await this.edit(s=>{if(this.now()<s.retryAt)return null;const events=Object.values(s.pending).slice(0,20),day=new Date(this.now()+8*3600000).toISOString().slice(0,10);if(!events.length&&(!register||s.ackDay===day))return null;s.retryAt=this.now()+retryMs;return {id:s.id,events:structuredClone(events),day,register:register&&s.ackDay!==day};});
        if(!snapshot)return;
        try{
          if(snapshot.events.length)await this.post('/v1/video-usage',{schema:1,installation_id:snapshot.id,app_version:this.version,client:'browser',events:snapshot.events});
          if(snapshot.register)await this.post('/v1/usage',{schema:1,installation_id:snapshot.id,app_version:this.version,active_days:[snapshot.day]});
        }catch{return;}
        await this.edit(s=>{for(const e of snapshot.events)if(JSON.stringify(s.pending[e.event_id])===JSON.stringify(e))delete s.pending[e.event_id];if(snapshot.register)s.ackDay=snapshot.day;s.retryAt=0;});
      }
    }finally{this.sending=false;}
  }
}
