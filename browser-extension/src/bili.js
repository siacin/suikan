import { AppError, LIMITS, checkAbort, checkedBiliUrl, parseVideoLink, metadata, sameSnapshot, subtitleTracks, checkedSubtitleUrl, subtitleSegments, hash } from './core.js';

async function textResponse(response, signal) {
  if (!response.body) return response.text();
  const reader = response.body.getReader(), decoder = new TextDecoder(); let text = '', bytes = 0;
  try {
    while (true) {
      checkAbort(signal); const { value, done } = await reader.read(); if (done) break;
      bytes += value.length;
      if (bytes > LIMITS.responseBytes) throw new AppError('LIMIT', 'B 站响应过大，已停止读取');
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally { await reader.cancel().catch(() => {}); }
}
export function biliTransport(fetcher = fetch) {
  async function request(value, signal, resolve = false) {
    const url = new URL(value), cdn = url.hostname === 'aisubtitle.hdslb.com' && url.pathname.startsWith('/bfs/');
    if (!cdn) checkedBiliUrl(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443') throw new AppError('SOURCE', 'B 站请求地址无效');
    const abort = new AbortController(), stop = () => abort.abort(); let timed = false;
    const timeout = setTimeout(() => { timed = true; abort.abort(); }, 25000);
    signal?.addEventListener('abort', stop, { once: true });
    try {
      checkAbort(signal);
      const response = await fetcher(value, { credentials: cdn ? 'omit' : 'include', cache: 'no-store', redirect: resolve ? 'follow' : 'error', signal: abort.signal });
      if (resolve) { checkedBiliUrl(response.url); return response.url; }
      if (!response.ok) throw new AppError('BILI', `B 站接口 HTTP ${response.status}，请稍后重试或在此浏览器登录 B 站`);
      const text = await textResponse(response, abort.signal);
      try { return JSON.parse(text.replace(/^\uFEFF/, '')); }
      catch { throw new AppError('BILI', 'B 站接口没有返回有效 JSON，可能需要登录或完成页面验证'); }
    } catch (error) {
      checkAbort(signal);
      if (timed) throw new AppError('BILI', 'B 站请求超过 25 秒，请检查网络并重试');
      throw error instanceof AppError ? error : new AppError('BILI', '读取 B 站时连接失败，请先在当前浏览器正常打开视频并登录');
    } finally { clearTimeout(timeout); signal?.removeEventListener('abort', stop); }
  }
  return { get: (url, signal) => request(url, signal), resolve: (url, signal) => request(url, signal, true) };
}
export async function acquire(input, { signal, progress = () => {}, transport = biliTransport() } = {}) {
  checkAbort(signal);
  progress('正在解析并核对视频链接');
  const link = await parseVideoLink(input, value => transport.resolve(value, signal));
  const readMeta = async () => metadata(link, await transport.get(`https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(link.bvid)}`, signal));
  progress('正在读取视频信息'); const meta = await readMeta(); checkAbort(signal);
  progress('正在查询完整字幕');
  const query = `?bvid=${meta.bvid}&aid=${meta.aid}&cid=${meta.cid}`; let tracks = [], firstError;
  for (const path of ['/x/player/wbi/v2', '/x/player/v2']) {
    try { tracks = subtitleTracks(await transport.get('https://api.bilibili.com' + path + query, signal), meta); }
    catch (error) { checkAbort(signal); if (error.code === 'SOURCE') throw error; firstError ||= error; }
    if (tracks.length) break;
  }
  const ranked = tracks.filter(t => typeof t.subtitle_url === 'string' && t.subtitle_url).map(t => ({ ...t, rank: t.lan?.startsWith('zh') ? 4 : t.lan?.startsWith('ai-zh') ? 3 : 1 })).sort((a, b) => b.rank - a.rank);
  if (!ranked.length) throw firstError || new AppError('BILI', '未找到可下载的完整字幕。请在当前浏览器登录 B 站并刷新视频；仍无字幕时可导入 TXT / SRT / VTT 文稿。');
  const track = ranked[0], url = checkedSubtitleUrl(track.subtitle_url, meta);
  const id = typeof track.id_str === 'string' ? track.id_str : typeof track.id === 'string' || Number.isSafeInteger(track.id) ? String(track.id) : '';
  if (!/^\d+$/.test(id) || /^0+$/.test(id)) throw new AppError('SOURCE', '视频来源校验未通过：字幕缺少可核对的轨道编号');
  progress('视频编号已核对，正在下载字幕');
  const segments = subtitleSegments(await transport.get(url, signal), meta); checkAbort(signal);
  progress('正在复核视频标题与字幕来源'); sameSnapshot(meta, await readMeta()); checkAbort(signal);
  return { ...meta, segments, subtitleId: id, subtitleUrlHash: await hash(url), transcriptHash: await hash(JSON.stringify(segments)), sourceLabel: `B站字幕 · ${track.lan_doc || track.lan || '未知语言'}`, acquiredAt: Date.now() };
}
export async function verifySource(source, { signal, transport = biliTransport() } = {}) {
  if (source.sourceKind !== 'bili') return;
  const link = await parseVideoLink(source.url);
  sameSnapshot(source, metadata(link, await transport.get(`https://api.bilibili.com/x/web-interface/view?bvid=${link.bvid}`, signal)));
}
