package com.suikan.app;
import java.io.*;import java.net.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLContext;import com.sun.net.httpserver.*;import org.json.JSONObject;
/** Real blocked TLS writes; backdate the activity clock to exercise the 120s watchdog quickly. */
final class UploadTests {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void blocked(Http.Control control,int attempt,Thread writer)throws Exception{
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30),stableSince=0,previous=-1;
  while(System.nanoTime()<until){
   if(!writer.isAlive())throw new AssertionError("writer unexpectedly completed");
   long bytes=control.uploadedBytes;
   if(control.requestAttempts==attempt&&control.connectionReady&&!control.requestSent&&bytes>0&&bytes<control.uploadTotalBytes){
    if(bytes!=previous){previous=bytes;stableSince=System.nanoTime();}
    else if(System.nanoTime()-stableSince>TimeUnit.SECONDS.toNanos(2))return;
   }else{previous=-1;stableSince=0;}
   Thread.sleep(50);
  }throw new AssertionError("did not create blocked upload");
 }
 static int run(SSLContext local)throws Exception{
  HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(local));
  CountDownLatch release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
  ExecutorService executor=Executors.newCachedThreadPool(task->{Thread t=new Thread(task,"upload-fixture");t.setDaemon(true);return t;});server.setExecutor(executor);
  server.createContext("/v1/chat/completions",exchange->{requests.incrementAndGet();try{release.await(90,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
  StringBuilder content=new StringBuilder(8*1024*1024);for(int i=0;i<8*1024*1024;i++)content.append('x');
  final String prompt=content.toString(),url="https://127.0.0.1:"+server.getAddress().getPort()+"/v1";content=null;
  Http.Control cancel=new Http.Control(),timeout=new Http.Control();AtomicReference<Throwable> cancelled=new AtomicReference<>(),timed=new AtomicReference<>();
  Thread a=new Thread(()->{try{Http.complete(url,"fixture","fixture-key",prompt,new JSONObject(),cancel);}catch(Throwable e){cancelled.set(e);}}),b=new Thread(()->{try{Http.complete(url,"fixture","fixture-key",prompt,new JSONObject(),timeout);}catch(Throwable e){timed.set(e);}});
  a.setDaemon(true);b.setDaemon(true);
  try{
   a.start();blocked(cancel,1,a);long at=System.nanoTime();cancel.cancel();a.join(5000);
   if(a.isAlive()){System.out.println("Blocked writer still active: "+a.getState());for(StackTraceElement frame:a.getStackTrace())System.out.println(frame);}
   if(cancelled.get()!=null)System.out.println("Cancel result: "+cancelled.get());
   check(!a.isAlive()&&cancelled.get() instanceof CancellationException,"cancel unblocks a real TLS writer");
   check(System.nanoTime()-at<TimeUnit.SECONDS.toNanos(5)&&cancel.requestAttempts==1&&!cancel.requestSent,"cancel is prompt and never retries");
   check(cancel.uploadedBytes>0&&cancel.uploadedBytes<cancel.uploadTotalBytes,"upload byte progress exposed before completion");
   b.start();java.lang.reflect.Field last=Http.Control.class.getDeclaredField("lastUpload");last.setAccessible(true);
   for(int attempt=1;attempt<=2;attempt++){
    blocked(timeout,attempt,b);last.setLong(timeout,System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(Http.MODEL_UPLOAD_IDLE_TIMEOUT_MS+5000L));
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(b.isAlive()&&timeout.requestAttempts==attempt&&System.nanoTime()<until)Thread.sleep(50);
   }b.join(5000);
   check(!b.isAlive()&&timed.get() instanceof Http.InterruptedGenerationException,"upload inactivity becomes bounded connection failure");
   check(timeout.requestAttempts==2&&timeout.uploadedRequests==0&&!timeout.requestSent&&requests.get()==3,"at most one full retry; no incomplete upload accepted");
   check(timed.get().getMessage().contains("120")&&Http.MODEL_UPLOAD_IDLE_TIMEOUT_MS==120000,"upload failure names inactivity limit");
   System.out.println("PASS "+checks+" blocked HTTPS upload checks (activity clock accelerated)");return checks;
  }finally{cancel.cancel();timeout.cancel();release.countDown();server.stop(0);executor.shutdownNow();}
 }
}
