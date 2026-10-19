import { AppError, checkAbort, pageIdentity, parseVideoLink, parseTranscript, hash, transcriptText, cleanSummary, depthLabel, summaryPrompt, stamp, safeFilename, markdown, redact } from './core.js';
import { acquire, verifySource } from './bili.js';
import { complete, modelBody } from './model.js';
import { readSettings, saveSettings, permissionPattern, forgetKey } from './settings.js';
import { listNotes, saveNote, deleteNote } from './db.js';
import { descriptor } from './analytics.js';

const $ = id => document.getElementById(id), params = new URLSearchParams(location.search);
const floating = params.get('view') === 'floating', boundTab = floating ? Number(params.get('tab')) : undefined;
let settings, source = null, note = null, context = {}, input = '', auto = true, epoch = 0, job = null, contextSequence = 0, toastTimer;
const stats = {}; let statusAt = 0, paintAt = 0;
async function send(message) { const reply = await chrome.runtime.sendMessage(message); if (!reply?.ok) throw new AppError('BROWSER', reply?.error || '浏览器操作未完成，请刷新 B 站页面'); return reply.data ?? reply; }
function toast(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 4000); }
function errorMessage(error) { return redact(error.message, settings?.apiKey || ''); }
function setTab(tab) {
  for (const button of document.querySelectorAll('[data-tab]')) { button.classList.toggle('selected', button.dataset.tab === tab); button.setAttribute('aria-current', button.dataset.tab === tab ? 'page' : 'false'); }
  for (const name of ['video', 'history', 'settings']) $('view-' + name).hidden = name !== tab;
  if (tab === 'history') renderHistory().catch(error => toast(errorMessage(error)));
}
function controls() {
  const available = !!input || source?.sourceKind === 'imported';
  $('extract').disabled = !!job || !input; $('summarize').disabled = !!job || !available;
  $('importButton').disabled = !!job; $('useLink').disabled = !!job; $('testModel').disabled = !!job;
  $('clearImport').hidden = source?.sourceKind !== 'imported';
  for (const button of document.querySelectorAll('[data-depth]')) button.disabled = !!job;
  $('emptyState').hidden = !!source || !!note || !!job;
}
function clearResult() { source = null; note = null; $('subtitleCard').hidden = true; $('noteCard').hidden = true; $('clearImport').hidden = true; controls(); }
function renderContext() {
  $('selectionKind').textContent = auto ? '当前视频' : source?.sourceKind === 'imported' ? '导入文稿' : '手动选择';
  $('videoTitle').textContent = source?.title || (context.identity && auto ? `B站视频 · ${context.identity.split(':')[0]}` : input ? '已选择视频链接' : '打开一个 B 站视频');
  $('videoMeta').textContent = source ? (source.sourceKind === 'bili' ? `${source.owner || 'B站视频'} · P${source.page} · ${stamp(source.duration)} · ${source.segments.length} 条字幕` : '用户导入，未核验与当前视频的关系') : auto && context.identity ? `P${context.identity.split(':p')[1]} · 提取后显示已核对的标题与完整字幕` : input ? '提取时会核对视频标题、编号和分 P' : '点击页面右下角「随看」或工具栏图标开始。';
  $('original').hidden = !source?.url && !context.identity;
  $('original').href = source?.url || (context.identity ? context.url : '#');
  $('showFloat').hidden = floating;
  controls();
}
async function refreshContext(force = false) {
  if (!auto && !force) return;
  const sequence = ++contextSequence;
  try {
    const next = await send({ type: 'CONTEXT', tabId: boundTab, windowId: floating ? undefined : context.windowId });
    if (sequence !== contextSequence) return;
    const changed = force || next.identity !== context.identity || next.tabId !== context.tabId;
    if (changed) {
      epoch++; if (job) { job.reason = '当前视频已改变，已停止旧任务'; job.controller.abort(); }
      auto = true; input = next.identity ? next.url : ''; clearResult();
    }
    context = next; if (!source || changed) renderContext();
  } catch (error) { if (force) toast(errorMessage(error)); }
}
function selectDepth(depth) {
  settings.depth = depth;
  for (const button of document.querySelectorAll('[data-depth]')) { button.classList.toggle('selected', button.dataset.depth === depth); button.setAttribute('aria-pressed', String(button.dataset.depth === depth)); }
  $('depthHint').textContent = { concise: '只保留主要结论与必要条件', standard: '抓住主要内容、关键理由与必要条件', detailed: '完整梳理观点、推理、例子与章节' }[depth];
  $('promptText').textContent = summaryPrompt(depth);
}
function status(kind, title, text) {
  $('statusCard').hidden = false; $('statusCard').className = 'card status ' + (kind || '');
  $('statusTitle').textContent = title; $('statusText').textContent = text;
  $('stop').hidden = !job; $('busyLine').hidden = !job;
  if (!job) { $('draft').hidden = true; $('statusStats').textContent = ''; }
}
function progress(update) {
  Object.assign(stats, typeof update === 'string' ? { phase: update } : update);
  const now = Date.now(); if (now - paintAt < 180) return; paintAt = now;
  $('statusText').textContent = stats.phase || '正在处理';
  const parts = [`已等待 ${Math.floor((now - statusAt) / 1000)} 秒`];
  if (stats.attempt) parts.push(`第 ${stats.attempt} 次请求`);
  if (stats.bodyBytes) parts.push(`完整请求 ${stats.bodyBytes.toLocaleString()} 字节`);
  if (stats.receivedBytes) parts.push(`已接收 ${stats.receivedBytes.toLocaleString()} 字节`);
  if (stats.textChars) parts.push(`正文 ${stats.textChars} 字`);
  $('statusStats').textContent = parts.join(' · ');
  const match = /"overview"\s*:\s*"((?:\\.|[^"\\])*)/.exec(stats.draft || '');
  $('draft').hidden = !match;
  if (match) $('draft').textContent = '生成中，尚未保存：\n' + match[1].replace(/\\n/g, '\n').replace(/\\"/g, '"').slice(0, 800);
}
function renderSubtitle() {
  $('subtitleCard').hidden = false; $('subtitleCount').textContent = `${source.segments.length} 条`;
  $('sourceStatus').textContent = source.sourceKind === 'bili' ? `视频标识与分 P 已核对 · ${source.bvid} · P${source.page}\n${source.sourceLabel}` : '用户导入文稿 · 未验证与视频的一致性';
  $('transcript').textContent = transcriptText(source, 'timed'); $('subtitleDetails').open = true;
  $('subtitleFormat').querySelector('[value=srt]').disabled = source.segments.some(row => row.start < 0 || row.end <= row.start);
  if ($('subtitleFormat').selectedOptions[0].disabled) $('subtitleFormat').value = 'plain';
  renderContext();
}
const element = (tag, text, className) => { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; };
function renderNote() {
  $('noteCard').hidden = false; $('noteTitle').textContent = note.source.title; $('noteDepth').textContent = depthLabel(note.depth); $('noteBody').replaceChildren();
  const body = $('noteBody'); body.append(element('p', note.summary.overview));
  for (const [field, title] of [['key_points', '核心要点'], ['chapters', '章节'], ['steps', '方法步骤']]) if (note.summary[field].length) {
    body.append(element('h3', title));
    for (const row of note.summary[field]) {
      body.append(element('h4', row.title));
      if (row.start >= 0 && note.source.sourceKind === 'bili') {
        const time = element('button', stamp(row.start), 'time'); time.title = '跳转到原视频此处';
        time.onclick = () => send({ type: 'SEEK', tabId: context.tabId, identity: pageIdentity(note.source.url), time: row.start }).catch(error => toast(errorMessage(error))); body.append(time);
      }
      if (field === 'steps') { const list = element('ol'); for (const text of row.items) list.append(element('li', text)); body.append(list); }
      else body.append(element('p', row.detail || row.summary));
      const evidence = element('details'), label = element('summary', '查看字幕依据'); evidence.append(label);
      const ids = new Set(row.segment_ids); evidence.append(element('div', note.source.segments.filter(row => ids.has(row.id)).map(row => (row.start >= 0 ? `[${stamp(row.start)}] ` : '') + row.text).join('\n'), 'evidence')); body.append(evidence);
    }
  }
  if (note.summary.caveats.length) { body.append(element('h3', '局限')); const list = element('ul'); for (const text of note.summary.caveats) list.append(element('li', text)); body.append(list); }
  controls();
}
async function ensureSelection(task) {
  checkAbort(task.controller.signal);
  if (task.epoch !== epoch) throw new DOMException('当前视频已改变', 'AbortError');
  if (task.auto) {
    const latest = await send({ type: 'CONTEXT', tabId: floating ? task.tabId : undefined, windowId: task.windowId });
    if (latest.identity !== task.identity || latest.tabId !== task.tabId) { task.reason = '当前视频已改变，已停止旧任务'; task.controller.abort(); checkAbort(task.controller.signal); }
  }
}
async function execute(kind, task) {
  const signal = task.controller.signal;
  await ensureSelection(task);
  if (kind === 'probe') {
    const probe = { title: '接口连接测试', segments: [{ id: 's1', text: 'connection test', start: -1, end: -1 }] };
    await complete(task.config, probe, 'concise', { signal, progress, promptOverride: '这是接口连接测试。只返回 JSON：{"ok":true}。不需要其他内容。' });
    return '模型连接测试通过。短请求可用，长视频仍取决于网络与服务商。';
  }
  if (!source || source.sourceKind !== 'imported') {
    if (kind === 'extract' || !source) {
      const acquired = await acquire(task.input, { signal, progress });
      await ensureSelection(task); source = acquired;
    } else { progress('正在重新核对视频来源'); await verifySource(source, { signal }); }
    await ensureSelection(task); renderSubtitle();
  }
  if (kind === 'extract') return '完整字幕已提取，可以复制或保存文件。';
  const selected = source;
  const pattern = permissionPattern(task.config.endpoint);
  if (!await chrome.permissions.contains({ origins: [pattern] })) throw new AppError('SETTINGS', '请先在设置中保存并授权模型接口访问');
  const video=descriptor(selected,task.depth);
  if(video)try{task.videoEvent=await send({type:'VIDEO_START',source:{...video,sourceKind:'bili',sourceCheckVersion:1},depth:task.depth});}catch{}
  await ensureSelection(task);
  const result = await complete(task.config, selected, task.depth, { signal, progress }); await ensureSelection(task);
  const summary = cleanSummary(result.text, selected);
  const id = `${selected.bvid || 'imported'}:${selected.cid || selected.transcriptHash}:${task.depth}`;
  const completed = { id, source: selected, summary, depth: task.depth, model: task.config.model, savedAt: Date.now(), modelCalls: result.calls };
  await ensureSelection(task); await saveNote(completed); await ensureSelection(task);
  note = completed; renderNote();
  task.videoOutcome='succeeded';
  return `${depthLabel(task.depth)}总结已保存 · ${result.calls} 次接口请求${result.networkRetries ? '（含一次整段重试）' : ''}`;
}
async function run(kind) {
  if (job) return;
  const task = { controller: new AbortController(), epoch, input, depth: settings.depth, config: { ...settings }, auto: kind !== 'probe' && auto, tabId: context.tabId, windowId: context.windowId, identity: context.identity, reason: '' };
  job = task; statusAt = Date.now(); paintAt = 0; for (const key of Object.keys(stats)) delete stats[key];
  status('', kind === 'extract' ? '正在提取字幕' : kind === 'probe' ? '正在测试连接' : '正在整理视频', '准备开始'); controls();
  const ticker = setInterval(() => progress({}), 1000);
  try {
    let message;
    const perform = async lock => { if (!lock) throw new AppError('BUSY', '另一个随看面板正在处理任务，请回到原面板查看或停止'); message = await execute(kind, task); };
    if (navigator.locks) await navigator.locks.request('suikan-active-task', { ifAvailable: true }, perform); else await perform(true);
    if (job === task) { job = null; status('success', '已完成', message); }
  } catch (error) {
    if (job === task) { job = null; status(error.name === 'AbortError' ? '' : 'error', error.name === 'AbortError' ? '已停止' : '这次任务未完成', task.reason || errorMessage(error)); }
  } finally {
    if(task.videoEvent)chrome.runtime.sendMessage({type:'VIDEO_END',id:task.videoEvent,status:task.videoOutcome||(task.controller.signal.aborted?'cancelled':'failed')}).catch(()=>{});
    clearInterval(ticker); if (job === task) job = null; controls();
  }
}
function download(text, filename, type = 'text/plain;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type })), link = element('a'); link.href = url; link.download = filename; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 5000);
}
async function copy(text) { try { await navigator.clipboard.writeText(text); toast('已复制'); } catch { toast('复制失败，请使用保存文件'); } }
async function renderHistory() {
  const list = $('historyList'); list.replaceChildren(); const notes = await listNotes();
  if (!notes.length) { list.append(element('p', '还没有保存的笔记。完成一次总结后会显示在这里。', 'empty-state')); return; }
  for (const row of notes) {
    const card = element('article', undefined, 'card history-item'); card.append(element('h3', row.source.title), element('span', `${depthLabel(row.depth)} · ${new Date(row.savedAt).toLocaleString()}`, 'date'), element('p', row.summary.overview, 'history-overview'));
    const actions = element('div', undefined, 'row');
    const view = element('button', '查看', 'secondary'); view.onclick = () => { epoch++; if (job) job.controller.abort(); auto = false; input = row.source.url || ''; source = row.source; note = row; renderSubtitle(); renderNote(); setTab('video'); };
    const save = element('button', '导出', 'secondary'); save.onclick = () => download(markdown(row), safeFilename(row.source.title, 'md'), 'text/markdown;charset=utf-8');
    const remove = element('button', '删除', 'text-button'); remove.onclick = async () => { await deleteNote(row.id); await renderHistory(); toast('已删除本地笔记'); };
    actions.append(view, save, remove); card.append(actions); list.append(card);
  }
}
function formSettings() {
  const values = { endpoint: $('endpoint').value.trim(), model: $('model').value.trim(), apiKey: $('apiKey').value.trim(), outputTokens: Number($('outputTokens').value), disableThinking: $('disableThinking').checked, lightGlm: $('lightGlm').checked, rememberKey: $('rememberKey').checked, depth: settings.depth };
  modelBody(values, 'JSON', { test: true }); return values;
}
async function saveForm() {
  const values = formSettings(), origin = permissionPattern(values.endpoint);
  // This is called directly by a click/submit, before any unrelated async operation.
  if (!await chrome.permissions.request({ origins: [origin] })) throw new AppError('PERMISSION', '未获得此接口的访问权限，设置尚未保存');
  await saveSettings(values); settings = values; return values;
}
async function init() {
  chrome.runtime.sendMessage({type:'ANALYTICS_FOREGROUND'}).catch(()=>{});
  settings = await readSettings(); selectDepth(settings.depth);
  for (const name of ['endpoint', 'model', 'apiKey', 'outputTokens']) $(name).value = settings[name] ?? '';
  for (const name of ['rememberKey', 'disableThinking', 'lightGlm']) $(name).checked = !!settings[name];
  try { context.windowId = (await chrome.windows.getCurrent()).id; } catch { /* Last focused window fallback. */ }
  for (const button of document.querySelectorAll('[data-tab]')) button.onclick = () => setTab(button.dataset.tab);
  for (const button of document.querySelectorAll('[data-depth]')) button.onclick = () => { selectDepth(button.dataset.depth); saveSettings(settings).catch(error => toast(errorMessage(error))); };
  $('refresh').onclick = () => refreshContext(true); $('showFloat').onclick = () => send({ type: 'SHOW_FLOAT', windowId: context.windowId, tabId: context.tabId }).catch(error => toast(errorMessage(error)));
  $('useLink').onclick = async () => { try { const value = $('linkInput').value.trim(); if (!value) throw new Error('请先粘贴视频链接'); if (!/https?:\/\//i.test(value) && !/BV[0-9A-Za-z]{10}/i.test(value)) throw new Error('未找到视频链接或 BV 号'); epoch++; auto = false; input = value; clearResult(); renderContext(); toast('已切换到手动链接'); } catch (error) { toast(errorMessage(error)); } };
  $('extract').onclick = () => run('extract'); $('summarize').onclick = () => run('summary'); $('stop').onclick = () => { if (job) { job.reason = '已停止任务，未保存不完整笔记'; job.controller.abort(); } };
  $('importButton').onclick = () => $('importFile').click();
  $('importFile').onchange = async () => {
    const file = $('importFile').files[0]; if (!file) return;
    try { if (file.size > 2 * 1024 * 1024) throw new Error('文稿文件过大'); const text = await file.text(), segments = parseTranscript(text); epoch++; auto = false; input = ''; clearResult(); source = { title: file.name.replace(/\.(txt|srt|vtt)$/i, '') + '（导入文稿）', sourceKind: 'imported', segments, transcriptHash: await hash(JSON.stringify(segments)), sourceLabel: '用户导入文稿，未核验原视频' }; renderSubtitle(); toast('已导入完整文稿'); } catch (error) { toast(errorMessage(error)); } finally { $('importFile').value = ''; }
  };
  $('clearImport').onclick = () => refreshContext(true);
  $('copySubtitle').onclick = () => { try { copy(transcriptText(source, $('subtitleFormat').value)); } catch (error) { toast(errorMessage(error)); } };
  $('saveSubtitle').onclick = () => { try { const format = $('subtitleFormat').value; download(transcriptText(source, format), safeFilename(source.title, format === 'srt' ? 'srt' : 'txt')); } catch (error) { toast(errorMessage(error)); } };
  $('copyNote').onclick = () => copy(markdown(note)); $('saveNote').onclick = () => download(markdown(note), safeFilename(note.source.title, 'md'), 'text/markdown;charset=utf-8');
  $('refreshHistory').onclick = () => renderHistory().catch(error => toast(errorMessage(error)));
  $('settingsForm').onsubmit = async event => { event.preventDefault(); try { await saveForm(); toast('模型设置已保存'); } catch (error) { toast(errorMessage(error)); } };
  $('testModel').onclick = async () => { try { await saveForm(); setTab('video'); run('probe'); } catch (error) { toast(errorMessage(error)); } };
  $('forgetKey').onclick = async () => { await forgetKey(); settings.apiKey = ''; $('apiKey').value = ''; toast('已清除密钥'); };
  chrome.runtime.onMessage.addListener(message => { if (message?.type === 'CONTEXT_CHANGED' && (!floating || message.tabId === boundTab)) refreshContext(); });
  window.addEventListener('pagehide', () => job?.controller.abort());
  await refreshContext(); controls();
}
init().catch(error => status('error', '初始化未完成', error.message || '请重新打开随看'));
