(() => {
  if (window !== window.top || document.getElementById('suikan-browser-root')) return;
  const host = document.createElement('div'); host.id = 'suikan-browser-root';
  const root = host.attachShadow({ mode: 'open' }), style = document.createElement('style');
  style.textContent = `:host{all:initial!important;font-family:system-ui,"Microsoft YaHei",sans-serif!important}
    .launcher{position:fixed;right:22px;bottom:94px;z-index:2147483646;display:flex;gap:4px;padding:5px;border:1px solid #fff8;border-radius:18px;background:#fff;box-shadow:0 5px 24px #28233225}
    button{font:600 13px system-ui,"Microsoft YaHei",sans-serif;border:0;border-radius:12px;padding:10px 13px;cursor:pointer;background:#f4f3f5;color:#37313c}button:hover{filter:brightness(.96)}.main{background:#ec3976;color:#fff}
    .window{position:fixed;top:86px;right:22px;width:min(430px,calc(100vw - 32px));height:min(740px,calc(100vh - 108px));z-index:2147483645;border:1px solid #e5dee5;border-radius:18px;overflow:hidden;background:#fff;box-shadow:0 16px 56px #30203130;resize:both;min-width:320px;min-height:330px;max-width:calc(100vw - 16px);max-height:calc(100vh - 16px);display:flex;flex-direction:column}
    .window[hidden],.launcher[hidden]{display:none}.bar{display:flex;align-items:center;gap:6px;background:#fff;padding:7px 10px;border-bottom:1px solid #f0ebef;touch-action:none;cursor:move}.bar span{flex:1;font-size:12px;color:#827781}.bar button{font-size:12px;padding:5px 9px}iframe{width:100%;flex:1;border:0;background:#faf9fc}.tip{position:fixed;bottom:150px;right:22px;max-width:300px;padding:10px 14px;border-radius:12px;background:#39303d;color:white;font:13px system-ui;z-index:2147483647}`;
  root.append(style);
  const launcher = document.createElement('div'); launcher.className = 'launcher';
  const floating = document.createElement('button'); floating.textContent = '随看'; floating.className = 'main'; floating.title = '打开视频笔记浮窗';
  const side = document.createElement('button'); side.textContent = '侧栏'; side.title = '在浏览器侧栏打开';
  launcher.append(floating, side); root.append(launcher); document.documentElement.append(host);
  let box, lastUrl = '';
  const valid = () => /^\/video\/BV[0-9A-Za-z]{10}\/?$/i.test(location.pathname);
  function toast(message) { const tip = document.createElement('div'); tip.className = 'tip'; tip.textContent = message; root.append(tip); setTimeout(() => tip.remove(), 5000); }
  async function openSide() { try { const result = await chrome.runtime.sendMessage({ type: 'OPEN_SIDE_PANEL' }); if (!result?.ok) toast(result?.error || '请点击工具栏的随看图标'); } catch { toast('扩展已更新，请刷新页面'); } }
  async function showFloat() {
    if (!valid()) return;
    if (!box) {
      let tabId; try { tabId = (await chrome.runtime.sendMessage({ type: 'PAGE_CONTEXT' }))?.tabId; } catch { toast('扩展已更新，请刷新页面'); return; }
      if (!Number.isInteger(tabId)) return;
      box = document.createElement('section'); box.className = 'window';
      const bar = document.createElement('div'); bar.className = 'bar'; const label = document.createElement('span'); label.textContent = '随看 · 拖动移动';
      const sidebar = document.createElement('button'); sidebar.textContent = '侧栏'; sidebar.onclick = openSide;
      const minimize = document.createElement('button'); minimize.textContent = '收起'; minimize.title = '隐藏浮窗，保留当前任务'; minimize.onclick = () => { box.hidden = true; };
      bar.append(label, sidebar, minimize); box.append(bar);
      const frame = document.createElement('iframe'); frame.title = '随看视频笔记'; frame.allow = 'clipboard-write'; frame.src = chrome.runtime.getURL(`panel.html?view=floating&tab=${tabId}`); box.append(frame); root.append(box);
      bar.addEventListener('pointerdown', event => {
        if (event.target.closest('button')) return;
        const rect = box.getBoundingClientRect(), x = event.clientX, y = event.clientY;
        bar.setPointerCapture(event.pointerId);
        const move = e => { box.style.right = 'auto'; box.style.left = Math.max(8, Math.min(innerWidth - box.offsetWidth - 8, rect.left + e.clientX - x)) + 'px'; box.style.top = Math.max(8, Math.min(innerHeight - box.offsetHeight - 8, rect.top + e.clientY - y)) + 'px'; };
        const stop = () => { bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', stop); bar.removeEventListener('pointercancel', stop); };
        bar.addEventListener('pointermove', move); bar.addEventListener('pointerup', stop); bar.addEventListener('pointercancel', stop);
      });
    }
    box.hidden = false;
  }
  floating.onclick = () => { if (box && !box.hidden) box.hidden = true; else showFloat(); }; side.onclick = openSide;
  function update() {
    launcher.hidden = !valid(); if (!valid() && box) box.hidden = true;
    if (lastUrl !== location.href) { lastUrl = location.href; chrome.runtime.sendMessage({ type: 'PAGE_CONTEXT' }).catch(() => {}); }
  }
  const timer = setInterval(update, 1000); update();
  window.addEventListener('pagehide', () => clearInterval(timer), { once: true });
  chrome.runtime.onMessage.addListener((request, sender, respond) => {
    if (sender.id !== chrome.runtime.id) return;
    if (request.type === 'SHOW_FLOAT') { showFloat().then(() => respond({ ok: true })); return true; }
    if (request.type === 'SEEK_VIDEO') {
      const page = new URL(location.href), identity = `${page.pathname.match(/BV[0-9A-Za-z]{10}/i)?.[0]}:p${Number(page.searchParams.get('p') || 1)}`;
      const video = document.querySelector('video');
      if (!video || identity !== request.identity || !Number.isFinite(request.time) || request.time < 0) { respond({ ok: false, error: '当前视频已改变或播放器尚未就绪' }); return; }
      video.currentTime = request.time; respond({ ok: true });
    }
  });
})();
