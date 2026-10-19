package com.suikan.app;
import org.json.*;
import java.util.*;
public final class VideoUsageTests {
 static int checks;static long now=1792382400000L;static List<JSONObject> requests=new ArrayList<>();static boolean fail;static Runnable during;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final class Memory implements UsageCounter.Storage {JSONObject value=new JSONObject();public JSONObject load(){try{return new JSONObject(value.toString());}catch(Exception e){throw new RuntimeException(e);}}public boolean save(JSONObject v){try{value=new JSONObject(v.toString());return true;}catch(Exception e){return false;}}}
 static JSONObject source()throws Exception{return new JSONObject().put("source_kind","bili").put("source_check_version",1).put("bvid","BV1xx411c7mD").put("aid",123).put("cid",456).put("page",2).put("title","测试视频").put("transcript","NEVER_UPLOAD").put("key","NEVER_UPLOAD").put("url","NEVER_UPLOAD");}
 static VideoUsageCounter create(Memory m){return new VideoUsageCounter(m,bytes->{JSONObject p=new JSONObject(new String(bytes,"UTF-8"));requests.add(p);if(during!=null)during.run();if(fail)throw new java.io.IOException("offline");},()->now,"10000000-0000-4000-8000-000000000001","0.1.17");}
 public static void main(String[] args)throws Exception{
  Memory m=new Memory();VideoUsageCounter c=create(m);
  check(c.start(source().put("source_kind","imported"),"standard").isEmpty(),"imports excluded");
  check(c.start(source().put("source_check_version",0),"standard").isEmpty(),"unverified sources excluded");
  check(c.start(source().put("cid",0),"standard").isEmpty(),"invalid identity excluded");
  String id=c.start(source(),"standard");check(UUID.fromString(id).version()==4,"random task identity");c.finish(id,UsageCounter.SUCCEEDED,true);c.finish(id,UsageCounter.FAILED,false);c.flush();
  JSONObject payload=requests.get(0),e=payload.getJSONArray("events").getJSONObject(0);check(payload.length()==5&&e.length()==11,"exact payload whitelist");check(!payload.toString().contains("NEVER_UPLOAD"),"no transcript key or URL");check(e.getString("status").equals("succeeded")&&e.getBoolean("cached"),"first terminal outcome and cache marker");check(m.value.getJSONArray("pending").length()==0,"ack clears pending");
  id=c.start(source(),"detailed");fail=true;c.flush();c.finish(id,UsageCounter.FAILED,false);int calls=requests.size();c.flush();check(requests.size()==calls,"backoff independent of task finish");c=create(m);c.flush();check(requests.size()==calls,"backoff survives restart");now+=UsageCounter.RETRY_MS;fail=false;c.flush();check(requests.get(requests.size()-1).getJSONArray("events").getJSONObject(0).getString("status").equals("failed"),"retry has terminal snapshot");
  final VideoUsageCounter racing=c;final String raceId=c.start(source(),"concise");during=()->{racing.finish(raceId,UsageCounter.CANCELLED,false);during=null;};calls=requests.size();c.flush();check(requests.size()==calls+2,"terminal during upload survives started ack");check(requests.get(requests.size()-1).getJSONArray("events").getJSONObject(0).getString("status").equals("cancelled"),"cancel uploaded after started");
  for(int j=0;j<45;j++){id=c.start(source(),"standard");c.finish(id,UsageCounter.SUCCEEDED,false);}calls=requests.size();c.flush();check(requests.size()==calls+3,"bounded queue batches");check(requests.get(calls).getJSONArray("events").length()==20&&requests.get(calls+2).getJSONArray("events").length()==5,"20 20 5 batching");
  c.start(source(),"standard");now+=32*86400000L;c.flush();check(m.value.getJSONArray("pending").length()==0||requests.size()==calls+3,"expired tasks not sent");
  System.out.println("Video usage tests passed: "+checks);
 }
}
