package com.suikan.app;
import java.io.*;import java.net.*;import java.security.KeyStore;import javax.net.ssl.*;
import java.util.concurrent.atomic.AtomicReference;import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;import com.sun.net.httpserver.*;
/** Real sockets and a private test certificate. Production trust settings never change. */
public final class HandshakeTests {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static boolean timeoutCause(Throwable e){for(int i=0;e!=null&&i<10;i++,e=e.getCause())if(e instanceof java.net.SocketTimeoutException)return true;return false;}
 public static void main(String[] args)throws Exception{
  KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(args[0])){store.load(in,"fixture-only".toCharArray());}
  KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"fixture-only".toCharArray());
  TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
  SSLContext local=SSLContext.getInstance("TLS");local.init(keys.getKeyManagers(),trust.getTrustManagers(),null);
  SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();HttpsURLConnection.setDefaultSSLSocketFactory(local.getSocketFactory());
  if(args.length>1&&args[1].equals("upload-only")){try{UploadTests.run(local);}finally{HttpsURLConnection.setDefaultSSLSocketFactory(previous);}return;}
  HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(local));
  server.createContext("/v1/chat/completions",exchange->{
   try{
    if(exchange.getRequestHeaders().getFirst("Cookie")!=null)throw new AssertionError("Bili cookie reached model");
    try(InputStream in=exchange.getRequestBody()){byte[] b=new byte[4096];while(in.read(b)!=-1){}}
    try{Thread.sleep(65000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    byte[] body="{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
    try(OutputStream out=exchange.getResponseBody()){out.write(body);}
   }finally{exchange.close();}
  });server.start();
  Http.Control tls=new Http.Control(),headers=new Http.Control();
  AtomicReference<Throwable> tlsError=new AtomicReference<>(),headerError=new AtomicReference<>();
  AtomicLong tlsSeconds=new AtomicLong(),headerSeconds=new AtomicLong();
  final String slowUrl="https://127.0.0.1:"+server.getAddress().getPort()+"/v1";
  Thread stalled=new Thread(()->{long at=System.nanoTime();try{Http.complete("https://127.0.0.1:18771/v1","fixture","fixture-key","Return JSON",new JSONObject(),tls);}catch(Throwable e){tlsError.set(e);}finally{tlsSeconds.set((System.nanoTime()-at)/1000000000L);}});
  Thread slow=new Thread(()->{long at=System.nanoTime();try{String answer=Http.complete(slowUrl,"fixture","fixture-key","Return JSON",new JSONObject(),headers);if(!Json.parseObject(answer).optBoolean("ok"))throw new AssertionError("missing result");}catch(Throwable e){headerError.set(e);}finally{headerSeconds.set((System.nanoTime()-at)/1000000000L);}});
  stalled.setDaemon(true);slow.setDaemon(true);
  try{
   stalled.start();slow.start();UploadTests.run(local);stalled.join(150000);slow.join(1000);
   if(stalled.isAlive()||slow.isAlive()){tls.cancel();headers.cancel();throw new AssertionError("phase timeout exceeded");}
   check(Http.MODEL_CONNECT_TIMEOUT_MS==60000&&Http.MODEL_IDLE_TIMEOUT_MS==900000,"connection and generation timeouts remain separate");
   Throwable failure=tlsError.get();
   check(failure instanceof Http.InterruptedGenerationException&&timeoutCause(failure)&&!((Http.InterruptedGenerationException)failure).canSplit&&!tls.connectionReady&&!tls.requestSent,"TLS timeout classified before any HTTP upload");
   check(HttpTests.count("tls-stall")==2,"TLS connection retry bounded to two attempts");
   check(tlsSeconds.get()>=115&&tlsSeconds.get()<145,"TLS does not wait for 15-minute generation timeout");
   if(headerError.get()!=null)headerError.get().printStackTrace();
   check(headerError.get()==null&&headers.connectionReady&&headers.requestSent,"65-second HTTPS model header wait remains successful");
   check(headerSeconds.get()>=60&&headerSeconds.get()<100,"long HTTPS response wait restored after connect");
   System.out.println("PASS "+checks+" handshake / HTTPS response checks; TLS="+tlsSeconds.get()+"s headers="+headerSeconds.get()+"s");
  }finally{server.stop(0);HttpsURLConnection.setDefaultSSLSocketFactory(previous);}
 }
}
