// Read-only connectivity check. Does not create fake installations or use an admin token.
const base=process.argv[2];
let url;
try {url=new URL(base);if(url.protocol!=='https:'||url.pathname!=='/'||url.search||url.hash||url.username||url.password)throw Error();}
catch {console.error('用法：node probe.mjs https://你的后台域名');process.exit(1);}
const start=Date.now();
try {
  const result=await fetch(new URL('/healthz',url),{signal:AbortSignal.timeout(10000),redirect:'error',cache:'no-store'});
  const body=await result.json();
  if(!result.ok||body.ok!==true)throw new Error(`HTTP ${result.status}：${body.error||'响应不正确'}`);
  console.log(`后台正常，耗时 ${Date.now()-start} 毫秒。本结果仅代表这台电脑的当前网络，仍需手机直连测试。`);
}catch(error){console.error('连接检查失败：'+error.message);process.exitCode=1;}
