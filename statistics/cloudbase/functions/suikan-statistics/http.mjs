import http from 'node:http';
import {Readable} from 'node:stream';
import {response} from './protocol.mjs';
export function createServer(handle){
  const server=http.createServer({maxHeaderSize:8192},async(req,res)=>{
    try{
      if(!req.url?.startsWith('/')||req.url.startsWith('//')){res.writeHead(400);res.end();return;}
      const body=['GET','HEAD'].includes(req.method)?undefined:Readable.toWeb(req);
      const request=new Request('http://service.local'+req.url,{method:req.method,headers:req.headers,body,...(body?{duplex:'half'}:{})});
      // Do not reflect CloudBase credential/context headers or trust client-supplied IP headers.
      const result=await handle(request,req.socket.remoteAddress||'local');
      res.writeHead(result.status,Object.fromEntries(result.headers));res.end(Buffer.from(await result.arrayBuffer()));
    }catch{const result=response({error:'invalid_request'},400);if(!res.headersSent)res.writeHead(result.status,Object.fromEntries(result.headers));res.end(await result.text());}
  });
  server.requestTimeout=10000;server.headersTimeout=10000;server.keepAliveTimeout=5000;
  return server;
}
