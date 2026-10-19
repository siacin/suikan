const fs=require('node:fs/promises'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const playwright=require(process.env.SUIKAN_PLAYWRIGHT||'playwright');
(async()=>{
 const output=path.resolve(process.argv[2]||'work/browser-smoke'),browserExe=process.env.SUIKAN_BROWSER_EXE;
 await fs.mkdir(output,{recursive:true});const actual=path.resolve(__dirname,'..'),extension=path.join(output,'fixture-extension');
 await fs.cp(actual,extension,{recursive:true,filter:p=>!p.includes(path.sep+'tests')});
 const manifest=JSON.parse(await fs.readFile(path.join(extension,'manifest.json'),'utf8'));
 // Only the loopback mock's host is pre-granted; runtime scripts are unchanged.
 manifest.host_permissions.push('http://127.0.0.1/*');await fs.writeFile(path.join(extension,'manifest.json'),JSON.stringify(manifest,null,2));
 const requests=[],analytics=[],errors=[];let context,checks=0,delaySubtitle=false,subtitleStarted=false;
 const check=(ok,why)=>{checks++;assert.ok(ok,why);console.log('PASS '+why);};
 const server=http.createServer(async(req,res)=>{
  const chunks=[];for await(const part of req)chunks.push(part);const body=JSON.parse(Buffer.concat(chunks));
  if(req.url==='/v1/usage'||req.url==='/v1/video-usage'){analytics.push({path:req.url,body,cookie:req.headers.cookie});res.writeHead(204);res.end();return;}
  requests.push({body,cookie:req.headers.cookie});
  if(body.model==='fixture-error'){res.writeHead(401,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{message:'fixture authorization rejected'}}));return;}
  if(body.model==='fixture-slow')await new Promise(r=>setTimeout(r,2000));
  const answer={overview:'浏览器端完整总结，覆盖开头和末尾。<script>window.injected=true</script>',key_points:[{title:'主要观点',detail:'基于真实字幕的要点。',segment_ids:['s1','s70']}],chapters:[],steps:[],caveats:[]};
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.write('data: '+JSON.stringify({choices:[{delta:{content:JSON.stringify(answer)},finish_reason:'stop'}]})+'\n\n');res.end();
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 await fs.writeFile(path.join(extension,'usage-statistics.json'),JSON.stringify({endpoint:'http://127.0.0.1:'+server.address().port+'/v1/usage'}));
 const endpoint='http://127.0.0.1:'+server.address().port+'/v1',BV='BV1bApq6LEeS',OTHER='BV18cpq62Exy',url='https://www.bilibili.com/video/'+BV;
 const view=bvid=>({code:0,data:{bvid,aid:bvid===BV?117433935334009:117433952109658,title:bvid===BV?'【什么是2.0】':'【牛末不牛初的应对】',owner:{name:'测试作者'},pages:[{page:1,cid:bvid===BV?101:303,duration:180,part:'第一P'},...(bvid===BV?[{page:2,cid:202,duration:180,part:'第二P'}]:[])]}});
 const rows=Array.from({length:70},(_,i)=>({from:i*2,to:i*2+1.5,content:i===69?'这是字幕末尾，全部显示。':`第${i+1}条完整字幕。观点和条件均保留。`}));
 try{
  const options={headless:true,viewport:{width:1280,height:960},acceptDownloads:true,args:['--disable-extensions-except='+extension,'--load-extension='+extension,'--no-first-run','--disable-background-networking']};
  if(browserExe)options.executablePath=browserExe;else options.channel='chromium';
  context=await playwright.chromium.launchPersistentContext(path.join(output,'profile-'+Date.now()),options);
  context.on('page',page=>page.on('pageerror',e=>errors.push(e.message)));
  await context.route('https://www.bilibili.com/**',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html><head><meta charset="utf-8"><title>B站视频测试</title></head><body style="background:#f5f5f7;font-family:Microsoft YaHei;padding:60px"><h1>【什么是2.0】</h1><div style="background:#252329;height:460px;width:760px;border-radius:14px;display:grid;place-items:center;color:#aaa">B站视频页面 · 测试夹具</div><video></video></body></html>'}));
  await context.route('https://api.bilibili.com/**',async route=>{
   const u=new URL(route.request().url()),bvid=u.searchParams.get('bvid')||BV;
   const data=u.pathname.includes('/view')?view(bvid):{code:0,data:{bvid,aid:view(bvid).data.aid,cid:Number(u.searchParams.get('cid')),page_no:Number(u.searchParams.get('cid'))===202?2:1,subtitle:{subtitles:[{id_str:'2127287793456724992',lan:'zh-CN',lan_doc:'中文',subtitle_url:'https://aisubtitle.hdslb.com/bfs/subtitle/'+bvid+'.json'}]}}};
   await route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  });
  await context.route('https://aisubtitle.hdslb.com/**',async route=>{
   subtitleStarted=true;if(delaySubtitle)await new Promise(r=>setTimeout(r,1800));
   const newer=route.request().url().includes(OTHER),body=newer?rows.map(row=>({...row,content:'新视频字幕：'+row.content})):rows;
   await route.fulfill({contentType:'application/json',body:JSON.stringify({body})}).catch(()=>{});
  });
  let worker=context.serviceWorkers()[0]||await context.waitForEvent('serviceworker');const id=new URL(worker.url()).host;
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(url);
  await page.locator('#suikan-browser-root button.main').click();
  const frame=page.frameLocator('#suikan-browser-root iframe');
  await frame.locator('#videoTitle').filter({hasText:BV}).waitFor();check(true,'扩展加载、当前视频识别与浮窗打开');
  await frame.locator('#extract').click();await frame.locator('#transcript').filter({hasText:'这是字幕末尾'}).waitFor();
  check((await frame.locator('#videoTitle').textContent()).includes('【什么是2.0】'),'真实括号标题保留');
  check((await frame.locator('#transcript').textContent()).includes('第1条完整字幕'),'字幕首尾完整展示');
  const size=await frame.locator('#transcript').evaluate(el=>({height:el.clientHeight,scroll:el.scrollHeight}));check(size.height<=240&&size.scroll>size.height,'字幕区固定高度、内部滚动');
  await frame.locator('#subtitleDetails summary').click();check(!await frame.locator('#transcript').isVisible(),'字幕可收起');await frame.locator('#subtitleDetails summary').click();
  await frame.locator('#copySubtitle').click();await frame.locator('#toast').filter({hasText:'已复制'}).waitFor();check(true,'字幕复制到剪贴板');
  const [download]=await Promise.all([page.waitForEvent('download'),frame.locator('#saveSubtitle').click()]);await download.saveAs(path.join(output,'subtitles.txt'));check((await fs.readFile(path.join(output,'subtitles.txt'),'utf8')).includes('这是字幕末尾'),'完整字幕下载');
  await frame.locator('#subtitleFormat').selectOption('srt');const [srt]=await Promise.all([page.waitForEvent('download'),frame.locator('#saveSubtitle').click()]);await srt.saveAs(path.join(output,'subtitles.srt'));check((await fs.readFile(path.join(output,'subtitles.srt'),'utf8')).includes('00:00:00,000 --> 00:00:01,500'),'SRT起止毫秒保留');
  await frame.locator('[data-tab=settings]').click();await frame.locator('#endpoint').fill(endpoint);await frame.locator('#model').fill('fixture-model');await frame.locator('#apiKey').fill('fixture-credential');await frame.locator('button[type=submit]').click();
  await frame.locator('#toast').filter({hasText:'模型设置已保存'}).waitFor();check(true,'设置保存与接口授权流程');
  const stored=await worker.evaluate(async()=>({local:await chrome.storage.local.get(null),session:await chrome.storage.session.get(null)}));check(!stored.local.modelKey&&stored.session.modelKey==='fixture-credential','默认仅会话保存API密钥');
  await frame.locator('[data-tab=video]').click();
  for(const depth of ['concise','standard','detailed']){
   await frame.locator(`[data-depth=${depth}]`).click();await frame.locator('#summarize').click();await frame.locator('#statusText').filter({hasText:'总结已保存'}).waitFor();
   check((await frame.locator('#noteBody').textContent()).includes('覆盖开头和末尾'),depth+'整段总结并保存');
  }
  check(requests.length===3,'三个档位各一次请求，无分批');
  await new Promise(r=>setTimeout(r,500));
  const videoEvents=analytics.filter(r=>r.path==='/v1/video-usage').flatMap(r=>r.body.events),terminal=videoEvents.filter(e=>e.status==='succeeded');
  check(new Set(terminal.map(e=>e.event_id)).size===3,'三个总结任务均上报成功，无提取字幕误计');
  check(terminal.every(e=>e.bvid===BV&&e.cid===101&&e.title==='【什么是2.0】 · P1 第一P'&&e.page===1),'视频统计来源与当前字幕一致');
  check(!JSON.stringify(analytics).includes('fixture-credential')&&!JSON.stringify(analytics).includes('字幕末尾')&&analytics.every(r=>!r.cookie),'统计不包含密钥、字幕或Cookie');
  check(requests.every(r=>JSON.parse(r.body.messages[1].content).transcript.includes('[s70] 这是字幕末尾')),'发送给模型的内容包含最终字幕');
  check(requests.every(r=>r.cookie===undefined),'模型请求没有B站Cookie');
  check(await frame.locator('#noteBody script').count()===0,'模型文本不作为HTML执行');
  await frame.locator('#noteBody details summary').first().click();check((await frame.locator('#noteBody .evidence').first().textContent()).includes('字幕末尾'),'可以查看真实字幕依据');
  await frame.locator('#noteBody button.time').first().click();check(await page.locator('video').evaluate(v=>v.currentTime)===0,'时间点跳转当前播放器');
  const [md]=await Promise.all([page.waitForEvent('download'),frame.locator('#saveNote').click()]);await md.saveAs(path.join(output,'note.md'));const exported=await fs.readFile(path.join(output,'note.md'),'utf8');check(exported.includes(BV)&&!exported.includes('fixture-credential'),'笔记导出带来源且不含密钥');
  await frame.locator('[data-tab=history]').click();await frame.locator('.history-item').first().waitFor();check(await frame.locator('.history-item').count()===3,'不同详细程度分别保存历史');
  await frame.locator('[data-tab=video]').click();await page.screenshot({path:path.join(output,'floating.png')});
  const setModel=async model=>{await frame.locator('[data-tab=settings]').click();await frame.locator('#model').fill(model);await frame.locator('button[type=submit]').click();await frame.locator('#toast').filter({hasText:'模型设置已保存'}).waitFor();await frame.locator('[data-tab=video]').click();};
  await setModel('fixture-error');await frame.locator('#summarize').click();await frame.locator('#statusTitle').filter({hasText:'这次任务未完成'}).waitFor();await new Promise(r=>setTimeout(r,300));
  check(analytics.filter(r=>r.path==='/v1/video-usage').flatMap(r=>r.body.events).some(e=>e.status==='failed'),'真实模型HTTP失败上报为失败');
  await setModel('fixture-slow');await frame.locator('#summarize').click();while(!requests.some(r=>r.body.model==='fixture-slow'))await new Promise(r=>setTimeout(r,30));await frame.locator('#stop').click();await frame.locator('#statusTitle').filter({hasText:'已停止'}).waitFor();await new Promise(r=>setTimeout(r,300));
  check(analytics.filter(r=>r.path==='/v1/video-usage').flatMap(r=>r.body.events).some(e=>e.status==='cancelled'),'用户停止模型请求上报为取消');
  await setModel('fixture-model');
  await frame.locator('#importFile').setInputFiles({name:'完整导入.srt',mimeType:'text/plain',buffer:Buffer.from('1\n00:00:01,000 --> 00:00:03,000\n用户导入全文末尾\n')});
  await frame.locator('#transcript').filter({hasText:'用户导入全文末尾'}).waitFor();check((await frame.locator('#sourceStatus').textContent()).includes('未验证'),'导入字幕独立标记，未借用网页视频标题');
  await frame.locator('#summarize').click();await frame.locator('#statusText').filter({hasText:'总结已保存'}).waitFor();await new Promise(r=>setTimeout(r,300));
  check(new Set(analytics.filter(r=>r.path==='/v1/video-usage').flatMap(r=>r.body.events).map(e=>e.event_id)).size===5,'导入文稿总结不伪装成视频分析上报');
  await frame.locator('#clearImport').click();await frame.locator('#videoTitle').filter({hasText:BV}).waitFor();check(!await frame.locator('#subtitleCard').isVisible(),'清除导入恢复当前视频');
  delaySubtitle=true;subtitleStarted=false;await frame.locator('#extract').click();while(!subtitleStarted)await new Promise(r=>setTimeout(r,50));
  await page.evaluate(other=>history.pushState({},'', '/video/'+other),OTHER);await frame.locator('#videoTitle').filter({hasText:OTHER}).waitFor();await new Promise(r=>setTimeout(r,2100));
  check(!await frame.locator('#subtitleCard').isVisible(),'视频切换后迟到字幕不会显示在新标题下');
  delaySubtitle=false;await frame.locator('#extract').click();await frame.locator('#transcript').filter({hasText:'新视频字幕'}).waitFor();check((await frame.locator('#sourceStatus').textContent()).includes(OTHER),'新视频重新提取与绑定');
  await page.locator('#suikan-browser-root .launcher button').nth(1).click();await new Promise(r=>setTimeout(r,1000));
  const panels=await worker.evaluate(()=>chrome.runtime.getContexts({contextTypes:['SIDE_PANEL']}));check(panels.length>0,'浏览器原生侧栏打开');
  const native=context.pages().find(p=>p.url().startsWith('chrome-extension://'+id+'/panel.html')&&p!==page);
  if(native){await native.locator('#videoTitle').filter({hasText:OTHER}).waitFor();check(true,'原生侧栏识别当前视频');await native.locator('#extract').click();await native.locator('#transcript').filter({hasText:'新视频字幕'}).waitFor();check(true,'原生侧栏完整提取字幕');await native.screenshot({path:path.join(output,'sidepanel.png')});}
  const unbound=await context.newPage();await unbound.goto('chrome-extension://'+id+'/panel.html');await page.bringToFront();
  await unbound.locator('#videoTitle').filter({hasText:OTHER}).waitFor();check(true,'侧栏共用界面自动识别活动视频');
  await unbound.evaluate(()=>document.getElementById('extract').click());await unbound.locator('#transcript').filter({hasText:'新视频字幕'}).waitFor();check(true,'侧栏共用界面完整提取');
  await unbound.setViewportSize({width:420,height:960});await unbound.screenshot({path:path.join(output,'sidepanel.png')});
  await fs.writeFile(path.join(output,'targets.json'),JSON.stringify({pages:context.pages().map(p=>p.url()),panels},null,2));
  check(errors.length===0,'浏览器运行无脚本异常：'+errors.join(';'));
  await fs.writeFile(path.join(output,'report.json'),JSON.stringify({ok:true,checks,requests:requests.length,browser:await context.browser()?.version(),runtimeScriptsUnchanged:true,testHostPreGranted:'http://127.0.0.1/*',mockBiliAndModel:true,extensionId:id,errors},null,2));
 }finally{await context?.close();server.closeAllConnections();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
