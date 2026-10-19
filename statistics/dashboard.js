"use strict";
const byId=id=>document.getElementById(id);
let token="",request=null,offset=0,selected=null;
let videoRequest=null,videoOffset=0,videoTotal=0;
const format=n=>Number(n).toLocaleString("zh-CN");
const emptyUsage=()=>({started:0,succeeded:0,failed:0,cancelled:0,extract:{started:0,succeeded:0,failed:0,cancelled:0},summary:{started:0,succeeded:0,failed:0,cancelled:0}});
function cell(row,value){const td=document.createElement("td");td.textContent=value;row.append(td);}
function render(data){
  byId("total").textContent=format(data.total_installations);byId("new").textContent=format(data.new_today);
  byId("dau").textContent=format(data.dau);byId("mau").textContent=format(data.mau_30d);
  const total=data.operations?.total||emptyUsage(),today=data.operations?.today||emptyUsage();
  byId("total-uses").textContent=format(total.started);byId("today-uses").textContent=format(today.started);
  byId("extract-uses").textContent=format(total.extract.started);byId("summary-uses").textContent=format(total.summary.started);
  byId("operations").replaceChildren();
  for(const [kind,name] of [["extract","字幕提取"],["summary","视频总结"]]){const row=document.createElement("tr");cell(row,name);for(const n of [total[kind].started,today[kind].started,total[kind].succeeded,total[kind].failed,total[kind].cancelled])cell(row,format(n));byId("operations").append(row);}
  byId("daily").replaceChildren();byId("versions").replaceChildren();byId("chart").replaceChildren();
  const max=Math.max(1,...data.daily.map(d=>Math.max(d.active,d.new)));
  data.daily.forEach((d,index)=>{
    const row=document.createElement("tr"),usage=d.usage||emptyUsage();cell(row,d.day);for(const n of [d.active,d.new,usage.extract.started,usage.summary.started,usage.succeeded,usage.failed,usage.cancelled])cell(row,format(n));byId("daily").prepend(row);
    const column=document.createElement("div");column.className="day-bar";column.title=`${d.day}：活跃 ${d.active}，新增 ${d.new}`;
    for(const key of ["active","new"]){const bar=document.createElement("div");bar.className=key==="new"?"bar new":"bar";bar.style.height=`${d[key]/max*100}%`;column.append(bar);}
    if((index%7===0&&index<27)||index===29){const label=document.createElement("span");label.className="day-label";label.textContent=d.day.slice(5);column.append(label);}byId("chart").append(column);
  });
  data.versions.forEach(v=>{const row=document.createElement("tr");cell(row,v.version);cell(row,format(v.installations));byId("versions").append(row);});
  if(!data.versions.length){const row=document.createElement("tr");cell(row,"暂无上报");cell(row,"0");byId("versions").append(row);}
  byId("report").hidden=false;byId("status").textContent=`统计日期 ${data.date} · 北京时间 · 刷新时间 ${new Date().toLocaleTimeString("zh-CN")}`;
}
function renderInstallations(data){
  offset=data.offset;byId("installations").replaceChildren();
  for(const item of data.items){
    const browserClient=item.app_version.endsWith('-browser');
    const row=document.createElement("tr");cell(row,item.id);row.lastChild.className="identifier";cell(row,item.app_version);cell(row,`${item.first_day} / ${item.last_day}`);
    const usage=item.usage||emptyUsage();cell(row,`${format(usage.started)} 次`);const detail=document.createElement("span");detail.className="usage-detail";detail.textContent=`提取 ${format(usage.extract.started)} / 总结 ${format(usage.summary.started)} · 成功 ${format(usage.succeeded)} / 失败 ${format(usage.failed)} / 取消 ${format(usage.cancelled)}`;row.lastChild.append(detail);
    if(browserClient)row.lastChild.textContent='见视频统计';
    cell(row,item.enabled?"可用":"已停用");if(!item.enabled)row.lastChild.className="disabled-state";
    if(item.reason){const reason=document.createElement("span");reason.className="access-reason";reason.textContent=item.reason;row.lastChild.append(reason);}
    const action=document.createElement("td"),button=document.createElement("button");button.textContent=item.enabled?"停用":"恢复";button.className="secondary";button.addEventListener("click",()=>editAccess(item));action.append(button);
    if(browserClient){button.textContent='浏览器仅统计';button.disabled=true;}
    const videos=document.createElement('button');videos.textContent='分析的视频';videos.className='secondary';videos.onclick=()=>{byId('video-user').value=item.id;byId('video-section').scrollIntoView({behavior:'smooth'});loadVideos();};action.append(videos);row.append(action);byId("installations").append(row);
  }
  if(!data.items.length){const row=document.createElement("tr");cell(row,"没有匹配的安装记录");row.firstChild.colSpan=6;byId("installations").append(row);}
  byId("page-info").textContent=`共 ${format(data.total)} 个安装实例 · 第 ${Math.floor(offset/50)+1} 页`;byId("previous").disabled=offset===0;byId("next").disabled=offset+data.limit>=data.total;
}
function editAccess(item){
  selected=item;byId("access-title").textContent=item.enabled?"停用此安装？":"恢复此安装？";byId("access-id").textContent=item.id;
  byId("access-description").textContent=item.enabled?"停用后将阻止新的字幕提取和总结；不删除已有笔记。":"恢复后允许此安装继续提取字幕和总结。";
  byId("reason").value=item.reason;byId("reason").disabled=!item.enabled;byId("confirm-access").textContent=item.enabled?"确认停用":"确认恢复";byId("access-dialog").showModal();
}
async function api(path,signal,body){
  const headers={Authorization:`Bearer ${token}`};if(body)headers["Content-Type"]="application/json";
  const response=await fetch(path,{method:body?"POST":"GET",headers,cache:"no-store",signal,body:body?JSON.stringify(body):undefined});
  if(!response.ok)throw new Error(response.status===401?"管理口令不正确。":response.status===429?"请求过于频繁，请稍后再试。":"管理服务暂时不可用，请刷新确认状态。");
  return response.json();
}
async function load(pageOffset=0){
  token=byId("token").value.trim()||token;if(!token){byId("status").textContent="请填写管理口令。";return;}
  if(request)request.abort();request=new AbortController();const current=request;const timeout=setTimeout(()=>current.abort(),15000);
  byId("load").disabled=true;byId("report").hidden=true;byId("status").textContent="正在读取统计…";
  try{
    const query=byId("search").value.trim();if(!/^[0-9a-f]{0,64}$/.test(query))throw new Error("安装编号只能包含小写十六进制字符。");
    const [data,installations]=await Promise.all([api("/api/summary",current.signal),api(`/api/installations?offset=${pageOffset}&limit=50&q=${query}`,current.signal)]);
    if(current!==request)return;byId("token").value="";render(data);renderInstallations(installations);loadVideos();
  }catch(error){if(current===request)byId("status").textContent=error.name==="AbortError"?"请求已取消或超时，请重试。":error.message;}
  finally{clearTimeout(timeout);if(current===request){request=null;byId("load").disabled=false;}}
}
byId("load").addEventListener("click",()=>load());byId("token").addEventListener("keydown",event=>{if(event.key==="Enter")load();});
byId("clear").addEventListener("click",()=>{if(request)request.abort();request=null;token="";byId("token").value="";byId("report").hidden=true;for(const id of ["daily","versions","chart","installations","operations"])byId(id).replaceChildren();byId("access-dialog").close();selected=null;for(const id of ["total","new","dau","mau","total-uses","today-uses","extract-uses","summary-uses"])byId(id).textContent="—";byId("load").disabled=false;byId("status").textContent="已退出，页面中的统计数据已清除。";});
byId('clear').addEventListener('click',()=>{videoRequest?.abort();videoRequest=null;for(const id of ['video-head','video-rows'])byId(id).replaceChildren();byId('video-status').textContent='';byId('video-page').textContent='';byId('video-filter').disabled=false;});

byId("filter").addEventListener("click",()=>load());byId("search").addEventListener("keydown",event=>{if(event.key==="Enter")load();});
byId("previous").addEventListener("click",()=>load(Math.max(0,offset-50)));byId("next").addEventListener("click",()=>load(offset+50));
byId("cancel-access").addEventListener("click",()=>byId("access-dialog").close());
byId("confirm-access").addEventListener("click",async()=>{
  if(!selected||request)return;const item=selected,reason=byId("reason").value.trim();byId("confirm-access").disabled=true;
  const current=new AbortController();request=current;const timeout=setTimeout(()=>current.abort(),15000);
  try{await api("/api/access",current.signal,{id:item.id,enabled:!item.enabled,reason});if(current!==request)return;byId("access-dialog").close();selected=null;request=null;await load(offset);}
  catch(error){if(current===request){byId("access-dialog").close();byId("status").textContent=error.name==="AbortError"?"请求超时，请刷新确认权限状态。":error.message;}}
  finally{clearTimeout(timeout);if(current===request)request=null;byId("confirm-access").disabled=false;}
});
const statusLabel={started:'未完成',succeeded:'成功',failed:'失败',cancelled:'取消'},depthLabel={concise:'简洁',standard:'标准',detailed:'详细'};
function timeLabel(ms){return new Date(ms).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});}
async function loadVideos(pageOffset=0){
  if(!token)return;videoRequest?.abort();const current=new AbortController();videoRequest=current;const timeout=setTimeout(()=>current.abort(),15000);
  byId('video-filter').disabled=true;byId('video-status').textContent='正在读取视频统计…';
  try{
    const p=new URLSearchParams({from:byId('video-from').value,to:byId('video-to').value,q:byId('video-user').value.trim(),bvid:byId('video-bvid').value.trim(),client:byId('video-client').value,offset:String(pageOffset),limit:'50'});
    if(!/^[0-9a-f]{0,64}$/.test(p.get('q')))throw new Error('安装编号格式不正确。');
    const events=byId('video-mode').value==='events',data=await api('/api/'+(events?'video-events':'videos')+'?'+p,current.signal);
    if(current!==videoRequest)return;videoOffset=data.offset;videoTotal=data.total;byId('video-head').replaceChildren();byId('video-rows').replaceChildren();
    const head=document.createElement('tr');for(const name of events?['视频 / 分 P','安装编号 / 客户端','开始 / 结束（北京时间）','结果 / 详细程度']:['视频 / 分 P','分析次数 / 用户数','成功 / 失败 / 取消 / 未完成','本地缓存 / 最近分析']){const th=document.createElement('th');th.textContent=name;head.append(th);}byId('video-head').append(head);
    for(const item of data.items){const row=document.createElement('tr'),td=document.createElement('td'),a=document.createElement('a');a.textContent=item.title;a.href=item.url;a.target='_blank';a.rel='noopener noreferrer';td.append(a);const detail=document.createElement('span');detail.className='usage-detail';detail.textContent=`${item.bvid} · P${item.page} · CID ${item.cid}`;td.append(detail);row.append(td);
      if(events){cell(row,item.id+'\n'+(item.client==='browser'?'浏览器':'安卓')+' '+item.app_version);row.lastChild.className='identifier';cell(row,timeLabel(item.started_at)+'\n'+(item.completed_at?timeLabel(item.completed_at):'—'));cell(row,statusLabel[item.status]+' / '+depthLabel[item.depth]+(item.cached?' / 本地缓存':''));}
      else{cell(row,`${format(item.attempts)} 次 / ${format(item.users)} 个安装`);cell(row,`${format(item.succeeded)} / ${format(item.failed)} / ${format(item.cancelled)} / ${format(item.unfinished)}`);cell(row,`${format(item.cached)} 次 / ${timeLabel(item.last_at)}`);}byId('video-rows').append(row);
    }
    if(!data.items.length){const row=document.createElement('tr');cell(row,'此条件下暂无新版客户端的视频分析记录');row.firstChild.colSpan=4;byId('video-rows').append(row);}
    const t=data.totals;byId('video-status').textContent=`${data.from} 至 ${data.to} · ${format(t.videos)} 个视频分 P · ${format(t.users)} 个安装 · 分析 ${format(t.attempts)} 次 · 成功 ${format(t.succeeded)} / 失败 ${format(t.failed)} / 取消 ${format(t.cancelled)} / 未完成 ${format(t.unfinished)}`;
    byId('video-page').textContent=`共 ${format(data.total)} 条 · 第 ${Math.floor(videoOffset/50)+1} 页`;byId('video-previous').disabled=videoOffset===0;byId('video-next').disabled=videoOffset+50>=videoTotal;
  }catch(error){if(current===videoRequest){byId('video-rows').replaceChildren();byId('video-status').textContent=error.name==='AbortError'?'查询超时，请重试。':'查询失败：'+error.message;}}
  finally{clearTimeout(timeout);if(current===videoRequest){videoRequest=null;byId('video-filter').disabled=false;}}
}
const videoToday=new Date(Date.now()+8*3600000).toISOString().slice(0,10),videoFrom=new Date(Date.now()+8*3600000-29*86400000).toISOString().slice(0,10);
byId('video-from').value=videoFrom;byId('video-to').value=videoToday;
for(const id of ['video-from','video-to']){byId(id).max=videoToday;byId(id).min=new Date(Date.now()+8*3600000-89*86400000).toISOString().slice(0,10);}
byId('video-filter').onclick=()=>loadVideos();byId('video-previous').onclick=()=>loadVideos(Math.max(0,videoOffset-50));byId('video-next').onclick=()=>loadVideos(videoOffset+50);
