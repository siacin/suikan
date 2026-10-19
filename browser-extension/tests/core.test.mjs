import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVideoLink, pageIdentity, sharedTitleMatches, metadata, sameSnapshot, subtitleTracks, checkedSubtitleUrl, subtitleSegments, parseTranscript, compact, cleanSummary, transcriptText, summaryPrompt, hash, safeFilename, markdown } from '../src/core.js';
import { acquire, biliTransport } from '../src/bili.js';
import { modelEndpoint, modelBody } from '../src/model.js';
import { BV, LINK, fixture, source, summary } from './fixtures.mjs';

test('真实括号标题完整保留，原链接与分P绑定', async () => {
  const link = await parseVideoLink('【什么是2.0】 https://b23.tv/C9D59Af', async () => LINK + '?p=2');
  assert.equal(link.sharedTitle, '【什么是2.0】'); assert.equal(link.bvid, BV); assert.equal(link.page, 2);
  assert.equal(pageIdentity(LINK + '?p=2&share_source=test'), `${BV}:p2`);
});
for (const [expected, actual, accepted] of [
  ['【什么是2.0】','【什么是2.0】',true], ['【牛末不牛初的应对】','【牛末不牛初的应对】',true],
  ['【【什么是2.0】】','【什么是2.0】',true], ['【什么是2.0】','什么是2.0',true],
  ['【什么是2.0】-哔哩哔哩','【什么是2.0】',true], ['【什么是2.0-哔哩哔哩】','什么是2.0',true],
  ['【什么是２．０】','【什么是2.0】',true], ['【 什么是2.0 】','【什么是2.0】',true],
  ['【什么是20】','【什么是2.0】',false], ['【牛末不牛初的应对】','【什么是2.0】',false],
  ['【【什么是2.0】】','什么是2.0',false], ['【什么是2.0','什么是2.0',false], ['', '', false]
]) test(`分享标题 ${expected} / ${actual}`, () => assert.equal(sharedTitleMatches(expected, actual), accepted));
for (const input of ['https://evil.example/video/'+BV, 'https://bilibili.com.evil.example/video/'+BV, 'https://evil@www.bilibili.com/video/'+BV, LINK+'?p=1&p=2', LINK+'?p=0', LINK+'?p=NaN', LINK+'?bvid=BV18cpq62Exy', LINK+' https://b23.tv/other', 'BV1bApq6LEeS BV18cpq62Exy', 'https://www.bilibili.com/read/'+BV])
  test(`拒绝歧义或错误链接 ${input}`, () => assert.rejects(parseVideoLink(input)));
test('短链跳转不允许外国域名或分P冲突', async () => {
  await assert.rejects(parseVideoLink('https://b23.tv/test', async () => 'https://evil.example/video/'+BV));
  await assert.rejects(parseVideoLink('https://b23.tv/test?p=2', async () => LINK+'?p=1'));
});
test('非整数形式的分P不能被数值转换默默接受',async()=>{await assert.rejects(parseVideoLink(LINK+'?p=1.0'));await assert.rejects(parseVideoLink(LINK+'?p=1e0'));});
test('无效字幕文件明确失败',()=>assert.throws(()=>subtitleSegments({body:{}},{duration:100}),/格式无效/));
test('完整生产提取路径两次元信息核对，首尾原文保留，大字幕ID保持精确', async () => {
  const f = fixture(), result = await acquire('【什么是2.0】 '+LINK, { transport: f });
  assert.equal(f.views, 2); assert.equal(f.downloads, 1); assert.equal(result.subtitleId, '2127287793456724992');
  assert.deepEqual(result.segments.map(s=>s.text), ['字幕开头','字幕末尾']); assert.equal(result.shareTitleChecked, true);
  assert.match(result.transcriptHash, /^[a-f0-9]{64}$/);
});
test('P2使用独立CID，不能回落到P1', async () => {
  const f=fixture();f.player.data.cid=202;f.player.data.page_no=2;
  const result=await acquire(LINK+'?p=2',{transport:f});assert.equal(result.cid,202);assert.match(f.requests[1],/cid=202/);
});
for (const [name, edit] of [
  ['错误BV', f=>f.view.data.bvid='BV18cpq62Exy'], ['缺少标题', f=>delete f.view.data.title],
  ['错误AV', f=>f.player.data.aid=1], ['错误CID', f=>f.player.data.cid=999], ['错误P', f=>f.player.data.page_no=2],
  ['接口标题不一致', f=>f.player.data.title='什么是2.0'], ['缺少字幕身份', f=>delete f.player.data.subtitle.subtitles[0].id_str],
  ['外国字幕地址', f=>f.player.data.subtitle.subtitles[0].subtitle_url='https://evil.example/subtitle'],
  ['重复CID', f=>f.view.data.pages[1].cid=101], ['无CID', f=>delete f.view.data.pages[0].cid],
  ['字幕URL含错误CID', f=>f.player.data.subtitle.subtitles[0].subtitle_url+='?cid=999'],
  ['字幕文件错误编号', f=>f.subtitle.cid=999], ['时间超出时长', f=>f.subtitle.body[0].to=200],
  ['时间倒置', f=>f.subtitle.body[0].from=3], ['下载后改标题', f=>f.mutateTitle=true]
]) test(`提取拦截：${name}`,async()=>{const f=fixture();edit(f);await assert.rejects(acquire(LINK,{transport:f}),error=>error.code==='SOURCE');});
test('错误分享标题在下载之前拦截',async()=>{const f=fixture();await assert.rejects(acquire('【错误标题】 '+LINK,{transport:f}));assert.equal(f.downloads,0);});
test('错误身份不能被备用字幕接口掩盖',async()=>{const f=fixture();f.player.data.cid=99;await assert.rejects(acquire(LINK,{transport:f}));assert.equal(f.requests.filter(u=>u.includes('/x/player/')).length,1);});
test('普通不可用接口允许校验过的备用接口',async()=>{const f=fixture(),get=f.get.bind(f);f.get=async u=>u.includes('/wbi/')?{code:-400}:get(u);assert.equal((await acquire(LINK,{transport:f})).segments.length,2);});
test('API之间标题比较不采用分享括号规则',()=>{const f=fixture(),link={bvid:BV,page:1};const m=metadata(link,f.view);assert.throws(()=>sameSnapshot(m,{...m,title:'什么是2.0'}));});
test('没有字幕明确报错，不调用模型',async()=>{const f=fixture();f.player.data.subtitle.subtitles=[];await assert.rejects(acquire(LINK,{transport:f}),/未找到/);assert.equal(f.downloads,0);});
test('取消提取不回落其他接口',async()=>{const f=fixture(),c=new AbortController();c.abort();await assert.rejects(acquire(LINK,{transport:f,signal:c.signal}),e=>e.name==='AbortError');});
test('B站API携带会话，CDN不发送会话或模型密钥',async()=>{const calls=[];const transport=biliTransport(async(u,o)=>{calls.push(o);return new Response('{}',{status:200});});await transport.get('https://api.bilibili.com/x/test');await transport.get('https://aisubtitle.hdslb.com/bfs/test');assert.equal(calls[0].credentials,'include');assert.equal(calls[1].credentials,'omit');assert.equal(calls[0].headers,undefined);});
test('SRT和VTT时间、毫秒、全文与TXT保留',()=>{
  const text='1\n00:00:01,125 --> 00:00:02,500\n第一行\n第二行\n\n2\n00:00:10,000 --> 00:00:11,000\n末尾\n';
  const rows=parseTranscript(text);assert.equal(rows[0].start,1.125);assert.equal(rows[0].text,'第一行\n第二行');assert.equal(rows.at(-1).text,'末尾');assert.match(transcriptText({segments:rows},'srt'),/00:00:01,125/);
  assert.equal(parseTranscript('WEBVTT\n\n00:00:02.500 --> 00:00:04.500 align:start\n内容')[0].start,2.5);
});
test('无时间文稿不编造时间，不能导出SRT',()=>{const rows=parseTranscript('普通文稿\n末尾');assert.equal(rows[0].start,-1);assert.throws(()=>transcriptText({segments:rows},'srt'));});
test('30万字符与8000条输入上限明确报错，不截断',()=>{assert.throws(()=>parseTranscript('字'.repeat(300001)));assert.throws(()=>parseTranscript('a\n'.repeat(8001)));});
test('核心提示词与安卓标准模式完全一致',async()=>assert.equal(await hash(summaryPrompt('standard')),'a0c0fd4faf522b3a8aec9848cf88db1291ce4e0899f7e7a012096f5241832b0f'));
test('模型引用只能来自真实字幕ID',()=>{const out=cleanSummary(JSON.stringify(summary),source);assert.equal(out.key_points[0].start,0);assert.deepEqual(out.key_points[0].segment_ids,['s1','s2']);assert.throws(()=>cleanSummary(JSON.stringify({...summary,key_points:[{...summary.key_points[0],segment_ids:['invented']}]}),source));});
test('非法模型JSON、缺正文或无依据不得保存',()=>{assert.throws(()=>cleanSummary('{"overview":"内嵌"引号"}',source));assert.throws(()=>cleanSummary('{}',source));assert.throws(()=>cleanSummary('{"overview":"x"}',source));});
test('Markdown导出带原视频和来源提示，文件名不含Windows非法字符',()=>{const note={source,summary:cleanSummary(JSON.stringify(summary),source),depth:'standard'};assert.match(markdown(note),new RegExp(BV));assert.equal(safeFilename('a/b:c?','md'),'a_b_c_.md');});
test('模型地址支持Chat和Responses，拒绝非HTTPS和地址中的密钥',()=>{assert.equal(modelEndpoint('https://example.com/v1'),'https://example.com/v1/chat/completions');assert.equal(modelEndpoint('https://example.com/v1/responses'),'https://example.com/v1/responses');for(const u of ['http://example.com/v1','https://user:secret@example.com/v1','https://example.com/v1?key=test'])assert.throws(()=>modelEndpoint(u));});
test('输出额度0不添加人为上限，深度思考配置保持一致',()=>{const {body}=modelBody({endpoint:'https://example.com/v1',model:'deepseek-v4.1-flash'},'JSON',{});assert.equal(body.max_tokens,undefined);assert.equal(body.thinking.type,'disabled');assert.equal(body.response_format.type,'json_object');assert.throws(()=>modelBody({endpoint:'https://example.com/v1',model:'x',outputTokens:100},'JSON',{}));});
