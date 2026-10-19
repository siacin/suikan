import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';
import { complete } from '../src/model.js';import { compact, cleanSummary } from '../src/core.js';import { source, summary, chat } from './fixtures.mjs';

const answer=JSON.stringify(summary), requests=new Map();
const server=http.createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);const body=JSON.parse(Buffer.concat(chunks).toString()||'{}'),model=body.model;
  if(!requests.has(model))requests.set(model,[]);const calls=requests.get(model);calls.push({body,cookie:req.headers.cookie,authorization:req.headers.authorization});
  const send=(data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(data));};
  if(model==='unauthorized')return send({error:{message:'invalid fixture-credential'}},401);
  if(model==='context')return send({error:{message:'context_length_exceeded'}},400);
  if(model==='unsupported'&&body.response_format)return send({error:{message:'response_format unsupported'}},400);
  if(model==='token-name'&&body.max_tokens)return send({error:{message:'use max_completion_tokens instead of max_tokens'}},400);
  if(model==='server-retry'&&calls.length===1)return send({error:{message:'temporary'}},502);
  if(model==='slow'){setTimeout(()=>{if(!res.destroyed)send(chat(answer));},120);return;}
  if(model==='plain'||model==='unsupported'||model==='token-name'||model==='server-retry'||!body.stream&&model!=='broken-twice')return send(chat(answer));
  if(model==='plain-responses')return send({status:'completed',output:[{content:[{type:'output_text',text:answer}]}]});
  res.writeHead(200,{'Content-Type':model==='unknown-mime'?'application/octet-stream':'text/event-stream'});
  if(model==='responses'){res.write('data: '+JSON.stringify({type:'response.output_text.delta',delta:answer})+'\n\n');res.end('data: '+JSON.stringify({type:'response.completed',response:{status:'completed',output:[{content:[{type:'output_text',text:answer}]}]}})+'\n\n');return;}
  if(model==='incomplete'){res.end('data: '+JSON.stringify({type:'response.incomplete'})+'\n\n');return;}
  if(model==='done-only'){res.end('data: [DONE]\n\n');return;}
  const event=JSON.stringify({choices:[{delta:{content:model==='bad-inner'?'not JSON':answer},finish_reason:model==='truncated'?'length':['broken','broken-twice'].includes(model)?null:'stop'}]});
  if(model==='multiline'){res.end('\uFEFFdata: '+event.slice(0,event.indexOf('['))+'\rdata: '+event.slice(event.indexOf('['))+'\r\r');return;}
  const output='\uFEFF: keepalive\r\ndata: '+event+'\r\n\r\n';
  if(model==='hold'){res.write(output);const timer=setTimeout(()=>res.end(),5000);res.on('close',()=>clearTimeout(timer));return;}
  if(model==='unknown-mime'){for(const char of output)res.write(char);res.end();return;}
  res.end(output);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base=`http://127.0.0.1:${server.address().port}/v1`;
test.after(()=>{server.closeAllConnections();server.close();});
const run=(model,options={},overrides={})=>complete({endpoint:base,model,apiKey:'fixture-credential',...overrides},source,'standard',{retryMs:1,...options});
for(const model of ['normal','plain','multiline','unknown-mime','responses','plain-responses'])test(`真实HTTP传输 ${model}`,async()=>{
  const output=await run(model,{},/responses/.test(model)?{endpoint:base+'/responses'}:{});assert.equal(output.text,answer);assert.equal(output.calls,1);assert.equal(cleanSummary(output.text,source).key_points.length,1);
});
test('收到完成标记后立即结束，无须等待连接关闭',async()=>{const at=Date.now();assert.equal((await run('hold')).text,answer);assert.ok(Date.now()-at<1500);});
test('首次中断仅完整重试一次，字幕首尾、提示词和散列不变',async()=>{
  const result=await run('broken');assert.equal(result.calls,2);assert.equal(result.networkRetries,1);
  const calls=requests.get('broken');assert.equal(calls[0].body.stream,true);assert.equal(calls[1].body.stream,false);assert.deepEqual(calls[0].body.messages,calls[1].body.messages);
  const input=JSON.parse(calls[1].body.messages[1].content);assert.equal(input.transcript,compact(source.segments));assert.ok(input.transcript.endsWith('[s2] 末尾强调适用条件。\n'));
});
test('连续不完整只发两次，不接受残缺回答',async()=>{await assert.rejects(run('broken-twice'));assert.equal(requests.get('broken-twice').length,2);});
test('过早DONE必须整段重试，不能把首轮当作完成',async()=>{const result=await run('done-only');assert.equal(result.calls,2);assert.equal(requests.get('done-only').length,2);});
test('截断不自动再付费重试',async()=>{await assert.rejects(run('truncated'),/截断/);assert.equal(requests.get('truncated').length,1);});
test('Responses未完成不接受',async()=>{await assert.rejects(run('incomplete',{}, {endpoint:base+'/responses'}),/截断/);assert.equal(requests.get('incomplete').length,1);});
test('明确拒绝JSON模式时适配，保留全量输入',async()=>{const result=await run('unsupported');assert.equal(result.calls,2);const calls=requests.get('unsupported');assert.ok(calls[0].body.response_format);assert.equal(calls[1].body.response_format,undefined);assert.deepEqual(calls[0].body.messages,calls[1].body.messages);});
test('明确拒绝max_tokens时仅改参数名',async()=>{await run('token-name',{}, {outputTokens:4096});const calls=requests.get('token-name');assert.equal(calls[1].body.max_completion_tokens,4096);assert.equal(calls[1].body.max_tokens,undefined);});
test('401错误不重试且不泄露密钥',async()=>{await assert.rejects(run('unauthorized'),error=>!error.message.includes('fixture-credential')&&error.message.includes('401'));assert.equal(requests.get('unauthorized').length,1);});
test('输入超限不分批、不重试',async()=>{await assert.rejects(run('context'),/不会拆分/);assert.equal(requests.get('context').length,1);});
test('502可以完整重试一次',async()=>assert.equal((await run('server-retry')).calls,2));
test('传输完成但内容格式错，不调用模型修补',async()=>{const result=await run('bad-inner');assert.throws(()=>cleanSummary(result.text,source));assert.equal(requests.get('bad-inner').length,1);});
test('模型请求不携带B站Cookie',async()=>{const calls=requests.get('normal');assert.equal(calls[0].cookie,undefined);assert.equal(calls[0].authorization,'Bearer fixture-credential');});
test('空闲等待和总时限使用真实HTTP验证（加速阈值）',async()=>{await assert.rejects(run('slow',{idleMs:20,totalMs:200}));assert.equal(requests.get('slow').length,2);});
test('用户取消不会重试',async()=>{const controller=new AbortController();const task=run('slow-cancel',{signal:controller.signal,fetcher:async(url,options)=>{await new Promise((resolve,reject)=>{options.signal.addEventListener('abort',()=>reject(new DOMException('abort','AbortError')),{once:true});setTimeout(resolve,1000);});return fetch(url,options);}});setTimeout(()=>controller.abort(),10);await assert.rejects(task,e=>e.name==='AbortError');assert.equal(requests.has('slow-cancel'),false);});
for(const depth of ['concise','standard','detailed'])test(`8000条字幕${depth}仍一次整段请求`,async()=>{
  const long={...source,segments:Array.from({length:8000},(_,i)=>({id:'s'+(i+1),start:i,end:i+1,text:i===7999?'这是最终字幕末尾':'全部字幕内容不能被裁剪。'}))};
  const model='long-'+depth,result=await complete({endpoint:base,model,apiKey:'fixture-credential'},long,depth,{retryMs:1});
  assert.equal(result.calls,1);const sent=JSON.parse(requests.get(model)[0].body.messages[1].content);assert.equal(sent.transcript,compact(long.segments));assert.ok(sent.transcript.includes('[s8000] 这是最终字幕末尾'));
});
