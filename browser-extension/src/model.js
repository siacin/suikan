import { AppError, LIMITS, checkAbort, compact, summaryPrompt, redact } from './core.js';
export const MODEL_IDLE_MS = 15 * 60 * 1000, MODEL_TOTAL_MS = 60 * 60 * 1000;
export function modelEndpoint(value) {
  let url; try { url = new URL(String(value || '').trim()); } catch { throw new AppError('SETTINGS', '请填写完整模型 API 地址'); }
  const local = ['localhost', '127.0.0.1'].includes(url.hostname);
  if (!(url.protocol === 'https:' || local && url.protocol === 'http:') || url.username || url.password || url.search || url.hash)
    throw new AppError('SETTINGS', '模型地址必须为 HTTPS（本机可用 HTTP），不能含账号、查询参数或片段');
  let path = url.pathname.replace(/\/+$/, '');
  if (!path) path = '/v1/chat/completions';
  else if (!/\/(?:chat\/completions|responses)$/.test(path)) path += '/chat/completions';
  url.pathname = path; return url.href;
}
export function modelBody(config, prompt, context, stream = true) {
  const endpoint = modelEndpoint(config.endpoint), responses = new URL(endpoint).pathname.endsWith('/responses');
  const limit = Number(config.outputTokens || 0);
  if (!Number.isSafeInteger(limit) || limit < 0 || limit > 0 && limit < 1024) throw new AppError('SETTINGS', '输出额度请填写 0 或不少于 1024 的整数');
  if (!String(config.model || '').trim()) throw new AppError('SETTINGS', '请填写模型名称');
  const body = { model: config.model.trim(), stream };
  if (responses) { body.instructions = prompt; body.input = JSON.stringify(context); if (limit) body.max_output_tokens = limit; }
  else { body.messages = [{ role: 'system', content: prompt }, { role: 'user', content: JSON.stringify(context) }]; body.response_format = { type: 'json_object' }; if (limit) body.max_tokens = limit; }
  if (config.disableThinking !== false && /^deepseek/i.test(body.model)) {
    if (responses) body.reasoning = { effort: 'none' }; else body.thinking = { type: 'disabled' };
  }
  if (config.lightGlm !== false && /^glm-5\.3/i.test(body.model)) {
    if (responses) body.reasoning = { effort: 'low' }; else { body.thinking = { type: 'enabled' }; body.reasoning_effort = 'low'; }
  }
  return { endpoint, body };
}
function content(value) {
  if (typeof value === 'string') return value;
  return Array.isArray(value) ? value.map(row => typeof row === 'string' ? row : row?.text || '').join('') : '';
}
function responseText(response) { return (response.output || []).flatMap(row => row.content || []).filter(row => row.type === 'output_text').map(row => content(row.text)).join(''); }
export function finalText(result) {
  if (result?.error) throw new AppError('MODEL', '模型返回错误，未保存不完整回答');
  if (Array.isArray(result?.choices) && result.choices.length) {
    const choice = result.choices[0];
    if (choice.finish_reason === 'length') throw new AppError('OUTPUT', '模型输出被服务商截断，请提高服务商支持的输出额度或调整总结程度');
    if (choice.message?.refusal) throw new AppError('MODEL', '服务商拒绝了本次请求');
    if (choice.finish_reason !== 'stop') throw new AppError('INCOMPLETE', '未收到模型正常完成标记，未保存笔记', true);
    const text = content(choice.message?.content);
    if (!text.trim()) throw new AppError('INCOMPLETE', '模型返回空正文，未保存笔记', true);
    return text;
  }
  if (result?.status === 'incomplete') throw new AppError('OUTPUT', '模型输出未完成或被截断，未保存笔记');
  if (result?.status === 'completed') {
    const text = responseText(result); if (text.trim()) return text;
    throw new AppError('INCOMPLETE', '模型返回空正文，未保存笔记', true);
  }
  throw new AppError('INCOMPLETE', '接口未返回完整 Chat / Responses 回答，未保存笔记', true);
}
export class SSEParser {
  constructor(onEvent) { this.onEvent = onEvent; this.line = ''; this.data = ''; this.cr = false; this.first = true; this.done = false; }
  feed(text) {
    for (const ch of text) {
      if (this.done) break;
      if (this.first) { this.first = false; if (ch === '\uFEFF') continue; }
      if (this.cr) { this.cr = false; if (ch === '\n') continue; }
      if (ch !== '\r' && ch !== '\n') { this.line += ch; if (this.line.length > 1024 * 1024) throw new AppError('PROTOCOL', '模型流式单行过大'); continue; }
      this.cr = ch === '\r'; const line = this.line; this.line = '';
      if (!line) {
        if (this.data) { const data = this.data.slice(0, -1); this.data = ''; this.done = this.onEvent(data) === true; }
      } else if (!line.startsWith(':')) {
        const split = line.indexOf(':'), field = split < 0 ? line : line.slice(0, split);
        if (field === 'data') {
          let value = split < 0 ? '' : line.slice(split + 1); if (value.startsWith(' ')) value = value.slice(1);
          this.data += value + '\n'; if (this.data.length > 2 * 1024 * 1024) throw new AppError('PROTOCOL', '模型流式事件过大');
        }
      }
    }
  }
}
class StreamResult {
  constructor(progress) { this.text = ''; this.reasoning = 0; this.finished = false; this.progress = progress; }
  event(data) {
    if (!data.trim()) return false;
    if (data.trim() === '[DONE]') { if (!this.finished) throw new AppError('INCOMPLETE', '流式回答提前结束，未收到完成标记', true); return true; }
    let event; try { event = JSON.parse(data); } catch { throw new AppError('PROTOCOL', '模型流式事件格式无效，未保存笔记', true); }
    if (event.error || ['error', 'response.failed'].includes(event.type)) throw new AppError('MODEL', '服务商返回流式错误，未保存笔记');
    const choice = event.choices?.[0];
    if (choice) {
      if (choice.delta?.refusal) throw new AppError('MODEL', '服务商拒绝了本次请求');
      this.text += content(choice.delta?.content); this.reasoning += content(choice.delta?.reasoning_content).length;
      if (choice.finish_reason) {
        if (choice.finish_reason === 'length') throw new AppError('OUTPUT', '模型输出被服务商截断，未保存笔记');
        if (choice.finish_reason !== 'stop') throw new AppError('MODEL', '模型未正常完成，未保存笔记');
        this.finished = true;
      }
    }
    if (event.type === 'response.output_text.delta') this.text += content(event.delta);
    if (event.type === 'response.refusal.delta') throw new AppError('MODEL', '服务商拒绝了本次请求');
    if (event.type === 'response.incomplete') throw new AppError('OUTPUT', '模型输出未完成或被截断，未保存笔记');
    if (event.type === 'response.completed') { this.text = finalText(event.response); this.finished = true; }
    this.progress({ textChars: this.text.length, reasoningChars: this.reasoning, draft: this.text });
    if (this.finished && !this.text.trim()) throw new AppError('INCOMPLETE', '模型返回结束标记但正文为空', true);
    return this.finished;
  }
}
async function readResult(response, signal, resetIdle, progress) {
  if (!response.body) return finalText(await response.json());
  const reader = response.body.getReader(), decoder = new TextDecoder(), result = new StreamResult(progress), parser = new SSEParser(data => result.event(data));
  let mode = /text\/event-stream/i.test(response.headers.get('content-type') || '') ? 'sse' : '', text = '', bytes = 0;
  try {
    while (true) {
      checkAbort(signal); const { value, done } = await reader.read(); if (done) break;
      resetIdle(); bytes += value.length; progress({ receivedBytes: bytes });
      if (bytes > LIMITS.responseBytes) throw new AppError('LIMIT', '模型响应过大，已停止读取');
      const part = decoder.decode(value, { stream: true });
      if (mode === 'sse') parser.feed(part);
      else {
        text += part;
        if (!mode) {
          const peek = text.replace(/^\uFEFF/, '').trimStart();
          if (/^(?:data:|event:|:)/.test(peek)) { mode = 'sse'; parser.feed(text); text = ''; }
          else if (peek.startsWith('{') || peek.startsWith('[')) mode = 'json';
          else if (peek.length > 10 && !'data:'.startsWith(peek) && !'event:'.startsWith(peek)) mode = 'json';
        }
      }
      if (parser.done) return result.text;
    }
    const tail = decoder.decode();
    if (mode === 'sse') { parser.feed(tail); if (parser.done) return result.text; throw new AppError('INCOMPLETE', '模型流式连接提前结束，未收到正常完成标记', true); }
    try { return finalText(JSON.parse((text + tail).replace(/^\uFEFF/, ''))); }
    catch (error) { if (error instanceof AppError) throw error; throw new AppError('INCOMPLETE', '接口响应不是完整 JSON，未保存笔记', true); }
  } finally { await reader.cancel().catch(() => {}); }
}
async function request(endpoint, body, key, { signal, deadline, progress, fetcher, idleMs }) {
  const controller = new AbortController(), stop = () => controller.abort(); let idleTimer, totalTimer, timeout = '';
  const resetIdle = () => { clearTimeout(idleTimer); idleTimer = setTimeout(() => { timeout = '等待响应数据超过 15 分钟'; controller.abort(); }, idleMs); };
  signal?.addEventListener('abort', stop, { once: true }); resetIdle();
  totalTimer = setTimeout(() => { timeout = '整次模型调用达到 60 分钟上限'; controller.abort(); }, Math.max(1, deadline - Date.now()));
  try {
    checkAbort(signal); const payload = JSON.stringify(body);
    progress({ phase: '正在连接并发送完整字幕，等待响应', bodyBytes: new TextEncoder().encode(payload).length, textChars: 0, reasoningChars: 0, receivedBytes: 0, draft: '' });
    const headers = { 'Content-Type': 'application/json', Accept: body.stream ? 'text/event-stream, application/json' : 'application/json' };
    if (key) headers.Authorization = 'Bearer ' + key;
    const response = await fetcher(endpoint, { method: 'POST', headers, body: payload, credentials: 'omit', redirect: 'error', cache: 'no-store', signal: controller.signal }); resetIdle();
    progress({ phase: `收到 HTTP ${response.status}，正在读取${body.stream ? '响应' : '完整 JSON'}`, httpStatus: response.status });
    if (!response.ok) {
      // Error bodies use the same read limit and timeout as successful responses.
      const reader = response.body?.getReader(), decoder = new TextDecoder(); let errorText = '', size = 0;
      if (reader) try { while (true) { const { done, value } = await reader.read(); if (done) break; resetIdle(); size += value.length; if (size > 65536) break; errorText += decoder.decode(value, { stream: true }); } } finally { await reader.cancel().catch(() => {}); }
      return { status: response.status, errorText };
    }
    return { status: response.status, text: await readResult(response, controller.signal, resetIdle, progress) };
  } catch (error) {
    checkAbort(signal);
    if (timeout) throw new AppError('TIMEOUT', timeout + '；未保存不完整笔记', Date.now() < deadline);
    if (error instanceof AppError) throw error;
    throw new AppError('NETWORK', '模型连接中断或请求未能完成。请检查接口网络可达性；未保存不完整笔记', true);
  } finally { clearTimeout(idleTimer); clearTimeout(totalTimer); signal?.removeEventListener('abort', stop); }
}
export async function complete(config, source, depth, { signal, progress = () => {}, fetcher = fetch, idleMs = MODEL_IDLE_MS, totalMs = MODEL_TOTAL_MS, retryMs = 2000, promptOverride } = {}) {
  const prompt = promptOverride || summaryPrompt(depth), context = { title: source.title, transcript: compact(source.segments) };
  const { endpoint, body } = modelBody(config, prompt, context), key = String(config.apiKey || '').trim(), deadline = Date.now() + totalMs;
  if (!key && !['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname)) throw new AppError('SETTINGS', '请先在设置中填写 API Key');
  let networkRetries = 0, formatAdapted = false, tokensAdapted = false, calls = 0;
  while (true) {
    checkAbort(signal); if (Date.now() >= deadline) throw new AppError('TIMEOUT', '整次模型调用达到等待上限');
    progress({ attempt: ++calls, stream: body.stream, phase: calls === 1 ? '准备一次总结全部字幕' : '正在提交同一份完整字幕' });
    try {
      const result = await request(endpoint, body, key, { signal, deadline, progress, fetcher, idleMs });
      if (result.text !== undefined) return { text: result.text, calls, networkRetries, transcriptChars: context.transcript.length };
      const error = result.errorText.toLowerCase();
      if (result.status === 400 && body.response_format && !formatAdapted && error.includes('response_format') && /unsupported|not support|unknown|unrecognized/.test(error)) {
        delete body.response_format; formatAdapted = true; continue;
      }
      if (result.status === 400 && body.max_tokens && !tokensAdapted && error.includes('max_tokens') && error.includes('max_completion_tokens')) {
        body.max_completion_tokens = body.max_tokens; delete body.max_tokens; tokensAdapted = true; continue;
      }
      if (result.status === 413 || [400, 422].includes(result.status) && /context_length_exceeded|maximum context length|context window|input token limit|prompt is too long|input is too long/.test(error))
        throw new AppError('CONTEXT', '完整字幕超过当前接口或模型的输入上限，请换用支持更长输入的模型；不会拆分字幕');
      let message = ''; try { message = JSON.parse(result.errorText).error?.message || ''; } catch { /* HTML errors are not shown. */ }
      throw new AppError('HTTP', `模型接口 HTTP ${result.status}${message ? '：' + redact(message, key).slice(0, 240) : '，请检查接口、密钥和模型名称'}`, [502, 503, 504].includes(result.status));
    } catch (error) {
      checkAbort(signal);
      if (!error.retryable || networkRetries || Date.now() + retryMs >= deadline) throw new AppError(error.code || 'MODEL', redact(error.message, key) + (networkRetries ? '（已整段重试一次）' : ''));
      networkRetries++; body.stream = false; progress({ phase: '连接未完成，正在准备一次完整 JSON 重试', draft: '' });
      await new Promise(resolve => { const timer = setTimeout(done, retryMs); function done() { clearTimeout(timer); signal?.removeEventListener('abort', done); resolve(); } signal?.addEventListener('abort', done, { once: true }); });
    }
  }
}
