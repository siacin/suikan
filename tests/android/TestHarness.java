package com.suikan.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs the production transport, encryption, evidence filtering and cache against a local fixture. */
public final class TestHarness extends Instrumentation {
    int checks=0;
    private void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();LocalStore store=new LocalStore(c);SharedPreferences prefs=c.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Map<String,?> original=new HashMap<>(prefs.getAll());Set<String> before=new HashSet<>();for(JSONObject n:store.list())before.add(n.optString("id"));
        int resultCode=Activity.RESULT_CANCELED;
        try{
            SecureSettings settings=new SecureSettings(c);settings.summaryDepth(SummaryDepth.DETAILED);String key="fixture-secret-"+System.nanoTime();settings.save("http://10.0.2.2:18765/v1","fixture",key);
            check(settings.key().equals(key),"encrypted key roundtrip");check(!prefs.getString("key_blob","").contains(key),"no plaintext key");
            JSONArray refs=SummaryEngine.validRefs(new JSONArray().put("s1").put("forged").put("s1"),new HashSet<>(java.util.Arrays.asList("s1","s2")));
            check(refs.length()==1&&refs.getString(0).equals("s1"),"fabricated and duplicate references filtered");
            StringBuilder transcript=new StringBuilder();String suffix="测试末尾-"+System.nanoTime();
            for(int i=0;i<8;i++){transcript.append("[00:").append(String.format(java.util.Locale.ROOT,"%02d",i+10)).append("] ");for(int j=0;j<1500;j++)transcript.append("知识点");transcript.append(i==7?suffix:"本段结束").append('\n');}
            final int[] progress={0};JSONObject note=new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),false,(p,m)->progress[0]=p);
            check(progress[0]==100&&note.optInt("chunk_count")>1,"full multi-chunk summary");
            check(note.getJSONArray("segments").toString().contains(suffix),"late video source retained");
            check(note.getJSONArray("points").length()>=note.optInt("chunk_count"),"each chunk contributes");
            check(!note.getJSONArray("points").toString().contains("forged"),"invalid model evidence removed");
            check(note.getJSONObject("summary").getJSONArray("chapters").getJSONObject(0).optDouble("start",-1)==10,"chapter time derived from source");
            String markdown=LocalStore.markdown(note);check(markdown.contains("字幕依据")&&!markdown.contains(key),"clean export with evidence");
            check(store.read(note.getString("id")).getJSONObject("summary").getString("overview").equals("完整测试总结"),"persisted note");
            settings.save("http://10.0.2.2:18765/v1","offline-cache",key);
            // First generation populates a cache under this model. A second request must not call the fixture.
            JSONObject cachedFirst=new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),false,(p,m)->{});
            JSONObject cachedSecond=new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),false,(p,m)->{});
            check(cachedFirst.getLong("saved_at")==cachedSecond.getLong("saved_at"),"cache reuses saved result");
            settings.save("http://10.0.2.2:18765/v1","invalid-refs",key);boolean failed=false;
            try{new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),true,(p,m)->{});}catch(Exception e){failed=true;}
            check(failed,"unsupported model references fail explicitly");check(store.read(note.getString("id")).has("summary"),"failed reanalysis preserves note");
            Http.Control cancelled=new Http.Control();cancelled.cancel();boolean stopped=false;try{Http.complete(settings.endpoint(),"fixture",key,"x",new JSONObject(),cancelled);}catch(java.util.concurrent.CancellationException e){stopped=true;}check(stopped,"cancelled network request does not start");
            check(Json.parseObject(Http.complete(settings.endpoint(),"sse-test",key,"x",new JSONObject(),new Http.Control())).optBoolean("ok"),"chat SSE decoded");
            check(Json.parseObject(Http.complete("http://10.0.2.2:18765/v1/responses","responses-test",key,"x",new JSONObject(),new Http.Control())).optBoolean("ok"),"Responses JSON decoded");
            check(Json.parseObject(Http.complete("http://10.0.2.2:18765/v1/responses","responses-sse",key,"x",new JSONObject(),new Http.Control())).optBoolean("ok"),"Responses SSE decoded");
            check(Json.parseObject(Http.complete(settings.endpoint(),"chunked-eof-once",key,"x",new JSONObject(),new Http.Control())).optBoolean("ok"),"Android chunked premature EOF recovers");
            boolean chunkedRejected=false;try{Http.complete(settings.endpoint(),"chunked-eof-always",key,"x",new JSONObject(),new Http.Control());}catch(Http.InterruptedGenerationException e){chunkedRejected=e.canSplit;}
            check(chunkedRejected,"Android persistent premature EOF is incomplete and eligible for bounded splitting");
            check(Json.parseObject(Http.complete(settings.endpoint(),"empty-sse-once",key,"x",new JSONObject(),new Http.Control())).optBoolean("ok"),"Android empty SSE retries once");
            boolean rejected=false;try{Http.endpoint("https://api.example/v1?key=secret");}catch(Exception e){rejected=true;}check(rejected,"secret query rejected");
            boolean truncated=false;try{Http.complete(settings.endpoint(),"truncated",key,"x",new JSONObject(),new Http.Control());}catch(Exception e){truncated=true;}check(truncated,"truncation explicit failure");
            settings.save("http://10.0.2.2:18765/v1","fixture",key);store.delete(note.getString("id"));
            SummaryService.begin(c,"",transcript.toString(),true);long deadline=android.os.SystemClock.uptimeMillis()+60000;JSONObject serviceNote=null;
            while(android.os.SystemClock.uptimeMillis()<deadline){try{serviceNote=store.read(note.getString("id"));if(serviceNote.has("summary"))break;}catch(Exception ignored){}Thread.sleep(200);}
            check(serviceNote!=null&&serviceNote.has("summary"),"foreground service completes saved-payload task");
            while(SummaryService.running&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
            java.io.File[] tasks=new java.io.File(c.getCacheDir(),"summary-tasks").listFiles();check(tasks==null||tasks.length==0,"temporary payload cleared");
            settings.save("http://10.0.2.2:18765/v1","checkpoint-fail",key);boolean lateFailed=false;
            try{new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),false,(p,m)->{});}catch(Exception e){lateFailed=e.getMessage().contains("HTTP 503");}
            java.io.File[] partial=new java.io.File(c.getCacheDir(),"summary-results").listFiles();check(lateFailed&&partial!=null&&partial.length>0,"Android retains completed batch after later model failure");
            final boolean[] restored={false};JSONObject resumedNote=new SummaryEngine(c,new Http.Control()).run("",transcript.toString(),false,(p,m)->{if(m.contains("恢复已完成"))restored[0]=true;});
            check(restored[0]&&resumedNote.getJSONArray("segments").toString().contains(suffix),"Android retry resumes completed work and retains source tail");
            partial=new java.io.File(c.getCacheDir(),"summary-results").listFiles();check(partial==null||partial.length==0,"checkpoint cleared only after complete note saved");
            int notesBeforeProbe=store.list().size();settings.save("http://10.0.2.2:18765/v1","telemetry-sse",key);
            Activity main=startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            SummaryService.probe(c);deadline=android.os.SystemClock.uptimeMillis()+30000;
            while(!SummaryService.message.contains("思考 16 字")&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
            check(SummaryService.message.contains("思考 16 字")&&SummaryService.message.contains("最近数据")&&!SummaryService.message.contains("private-thinking"),"native service publishes live counts without reasoning text");
            final Object[] title={null};runOnMainSync(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("statusTitle");field.setAccessible(true);title[0]=field.get(main);}catch(Exception e){throw new RuntimeException(e);}});
            check(title[0]!=null,"progress view exists during connection probe");
            while(SummaryService.running&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
            check(!SummaryService.running&&SummaryService.error.isEmpty()&&SummaryService.message.contains("测试通过"),"short connection test completes via foreground service");
            check(store.list().size()==notesBeforeProbe,"connection probe does not create a video note");
            String finalMessage=SummaryService.message;Thread.sleep(1500);check(SummaryService.message.equals(finalMessage),"heartbeat stops after completion");
            settings.save("http://10.0.2.2:18765/v1","slow-sse",key);SummaryService.probe(c);deadline=android.os.SystemClock.uptimeMillis()+15000;
            while(!SummaryService.modelWaiting&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
            check(SummaryService.running&&SummaryService.modelWaiting,"slow stream remains visibly active");
            long stoppedAt=android.os.SystemClock.uptimeMillis();c.startService(new android.content.Intent(c,SummaryService.class).setAction(SummaryService.STOP));
            while(SummaryService.running&&android.os.SystemClock.uptimeMillis()-stoppedAt<5000)Thread.sleep(50);
            check(!SummaryService.running&&SummaryService.message.equals("已停止任务"),"native stream cancellation completes within five seconds");
            settings.save("http://10.0.2.2:18765/v1","auth-error",key);SummaryService.probe(c);deadline=android.os.SystemClock.uptimeMillis()+15000;
            while(!SummaryService.running&&SummaryService.error.isEmpty()&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
            while(SummaryService.running&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
            check(SummaryService.error.contains("HTTP 401")&&!SummaryService.error.contains(key)&&store.list().size()==notesBeforeProbe,"failed connection probe redacts secrets and preserves notes");
            result.putString("stream","PASS "+checks+" Android integration checks\n");result.putInt("checks",checks);resultCode=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));}
        finally{
            for(JSONObject note:store.list())if(!before.contains(note.optString("id")))store.delete(note.optString("id"));
            SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){Object value=entry.getValue();if(value instanceof String)edit.putString(entry.getKey(),(String)value);else if(value instanceof Integer)edit.putInt(entry.getKey(),(Integer)value);else if(value instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Long)edit.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(entry.getKey(),(Float)value);}edit.commit();
        }
        finish(resultCode,result);
    }
}
