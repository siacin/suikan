import { pageIdentity } from './src/core.js';
import { VideoAnalytics } from './src/analytics.js';
const analyticsReady=(async()=>{
  const config=await (await fetch(chrome.runtime.getURL('usage-statistics.json'))).json();
  const address=new URL(config.endpoint);if(address.pathname!=='/v1/usage'||address.username||address.password||address.search||address.hash||!(address.protocol==='https:'||address.hostname==='127.0.0.1'))throw new Error('Invalid statistics endpoint');
  return new VideoAnalytics({version:chrome.runtime.getManifest().version+'-browser',load:async()=>(await chrome.storage.local.get('videoAnalytics')).videoAnalytics,save:async state=>chrome.storage.local.set({videoAnalytics:state}),post:async(path,body)=>{
    const r=await fetch(address.origin+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',redirect:'error',cache:'no-store',signal:AbortSignal.timeout(5000)});if(r.status!==204)throw new Error('Statistics unavailable');
  }});
})();analyticsReady.catch(()=>{});
const flushAnalytics=()=>analyticsReady.then(a=>a.flush()).catch(()=>{});
chrome.alarms.create('video-analytics',{periodInMinutes:15});
chrome.alarms.onAlarm.addListener(alarm=>{if(alarm.name==='video-analytics')flushAnalytics();});
const extensionOrigin = chrome.runtime.getURL('');
async function setup() {
  await chrome.storage.local.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });
  await chrome.storage.session.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });
  if (chrome.sidePanel?.setPanelBehavior) await chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
  await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [1], addRules: [{ id: 1, priority: 1,
    action: { type: 'modifyHeaders', requestHeaders: [{ header: 'Referer', operation: 'set', value: 'https://www.bilibili.com/' }] },
    condition: { initiatorDomains: [chrome.runtime.id], requestDomains: ['api.bilibili.com', 'aisubtitle.hdslb.com'], resourceTypes: ['xmlhttprequest'] }
  }] });
}
chrome.runtime.onInstalled.addListener(() => setup().catch(() => {}));
chrome.runtime.onStartup.addListener(() => setup().catch(() => {}));
setup().catch(() => {});
const publish = event => chrome.runtime.sendMessage({ type: 'CONTEXT_CHANGED', ...event }).catch(() => {});
chrome.tabs.onUpdated.addListener((tabId, info) => { if (info.url) publish({ tabId, url: info.url }); });
chrome.tabs.onActivated.addListener(info => publish(info));
chrome.tabs.onRemoved.addListener(tabId => publish({ tabId, removed: true }));
function trusted(sender) { return sender.id === chrome.runtime.id && typeof sender.url === 'string' && sender.url.startsWith(extensionOrigin); }
async function context(request) {
  let tab;
  if (Number.isInteger(request.tabId)) tab = await chrome.tabs.get(request.tabId);
  else [tab] = await chrome.tabs.query({ active: true, ...(Number.isInteger(request.windowId) ? { windowId: request.windowId } : { lastFocusedWindow: true }) });
  if (!tab) return { tabId: null, url: '', identity: '' };
  return { tabId: tab.id, windowId: tab.windowId, url: tab.url || '', identity: pageIdentity(tab.url || '') };
}
chrome.runtime.onMessage.addListener((request, sender, respond) => {
  if (!request || typeof request.type !== 'string') return;
  // Open synchronously in the user gesture's message handler.
  if (request.type === 'OPEN_SIDE_PANEL') {
    if (sender.id !== chrome.runtime.id || (!trusted(sender) && !pageIdentity(sender.tab?.url || ''))) return;
    const windowId = sender.tab?.windowId ?? request.windowId;
    if (!Number.isInteger(windowId) || !chrome.sidePanel?.open) { respond({ ok: false, error: '此浏览器暂不能打开侧栏，请使用网页浮窗' }); return; }
    chrome.sidePanel.open({ windowId }).then(() => respond({ ok: true }), () => respond({ ok: false, error: '请点击浏览器工具栏的随看图标打开侧栏' }));
    return true;
  }
  if (request.type === 'PAGE_CONTEXT') {
    if (sender.id === chrome.runtime.id && sender.tab && sender.url?.startsWith('https://www.bilibili.com/')) publish({ tabId: sender.tab.id, windowId: sender.tab.windowId });
    respond({ ok: true, tabId: sender.tab?.id }); return;
  }
  if (!trusted(sender)) return;
  if(['VIDEO_START','VIDEO_END','ANALYTICS_FOREGROUND'].includes(request.type)){
    analyticsReady.then(async a=>{
      if(request.type==='VIDEO_START'){const id=await a.start(request.source,request.depth);flushAnalytics();return {ok:true,data:id};}
      if(request.type==='VIDEO_END'){await a.finish(request.id,request.status);flushAnalytics();return {ok:true};}
      a.foreground().catch(()=>{});return {ok:true};
    }).then(respond,()=>respond({ok:true,data:''}));return true;
  }
  if (request.type === 'CONTEXT') {
    context(request).then(data => respond({ ok: true, data }), () => respond({ ok: false, error: '无法读取当前标签页，请重新打开 B 站视频' })); return true;
  }
  if (request.type === 'SEEK') {
    context(request).then(async data => {
      if (!data.identity || data.identity !== request.identity || !Number.isFinite(request.time) || request.time < 0) throw new Error('当前视频已经改变，未跳转');
      return chrome.tabs.sendMessage(data.tabId, { type: 'SEEK_VIDEO', identity: data.identity, time: request.time });
    }).then(data => respond(data), error => respond({ ok: false, error: error.message })); return true;
  }
  if (request.type === 'SHOW_FLOAT') {
    context(request).then(async data => { if (!data.identity) throw new Error('请先打开 B 站视频'); return chrome.tabs.sendMessage(data.tabId, { type: 'SHOW_FLOAT' }); })
      .then(data => respond(data), () => respond({ ok: false, error: '请刷新 B 站视频页后再打开浮窗' })); return true;
  }
});
