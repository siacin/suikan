package com.suikan.app;
import org.json.JSONObject;
public class TimeoutChecks {
 public static void main(String[] args)throws Exception {
  Http.Control cancel=new Http.Control();final boolean[] stopped={false};
  Thread request=new Thread(()->{try{Http.complete("http://127.0.0.1:18765/v1","slow-sse","fixture","x",new JSONObject(),cancel);}catch(java.util.concurrent.CancellationException e){stopped[0]=true;}catch(Exception e){System.out.println(e.getMessage());}});
  request.start();Thread.sleep(3000);long begin=System.nanoTime();cancel.cancel();request.join(5000);
  if(request.isAlive()||!stopped[0]||(System.nanoTime()-begin)>5000000000L)throw new AssertionError("cancel must stop streaming promptly");
  System.out.println("PASS streaming cancellation within 5 seconds");
  begin=System.nanoTime();String text=Http.complete("http://127.0.0.1:18765/v1","delayed-model","fixture","x",new JSONObject(),new Http.Control());
  long elapsed=(System.nanoTime()-begin)/1000000000;
  if(!Json.parseObject(text).optBoolean("ok")||elapsed<190)throw new AssertionError("long wait failed");
  System.out.println("PASS delayed first response after "+elapsed+" seconds (exceeds previous 180-second timeout)");
 }
}
