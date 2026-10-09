package com.suikan.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.TextView;
import org.json.*;
import java.util.*;

/** Installed-APK request count and draft lifecycle, using an emulator-local model. */
public final class SpeedHarness extends Instrumentation {
    int checks;
    void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    int count(String model)throws Exception{java.net.HttpURLConnection conn=(java.net.HttpURLConnection)new java.net.URL("http://10.0.2.2:18765/counts").openConnection();try(java.io.InputStream in=conn.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).optInt(model);}finally{conn.disconnect();}}
    void finishTask(long deadline)throws Exception{while(SummaryService.running&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);check(!SummaryService.running,"foreground task terminates");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();LocalStore store=new LocalStore(c);SharedPreferences prefs=c.getSharedPreferences("settings",0);Map<String,?> original=new HashMap<>(prefs.getAll());Set<String> before=new HashSet<>();for(JSONObject n:store.list())before.add(n.optString("id"));int code=Activity.RESULT_CANCELED;
        try{
            SecureSettings settings=new SecureSettings(c);settings.save("http://10.0.2.2:18765/v1","fast-native","fixture-key");settings.summaryDepth(SummaryDepth.STANDARD);
            StringBuilder source=new StringBuilder();for(int i=1;i<=1077;i++)source.append("[00:01] 字幕第").append(i).append("段\n");String input=source.toString();
            int previous=count("fast-native");JSONObject note=new SummaryEngine(c,new Http.Control()).run("",input,true,(p,m)->{});
            check(count("fast-native")==previous+1&&note.getInt("model_steps")==1,"1077 cues use exactly one actual model POST");
            check(note.getInt("chunk_count")==1&&note.getJSONArray("segments").length()==1077,"one compact request preserves all 1077 source cues");
            check(note.getJSONArray("points").toString().contains("字幕第1077段")&&!note.toString().contains("forged"),"last cue remains available as verified evidence");
            check(note.getJSONObject("summary").getJSONArray("key_points").getJSONObject(0).getDouble("start")==1,"timestamps are attached locally");
            previous=count("fast-native");JSONObject cached=new SummaryEngine(c,new Http.Control()).run("",input,false,(p,m)->{});check(count("fast-native")==previous&&cached.getLong("saved_at")==note.getLong("saved_at"),"saved fast note reuses cache without another POST");
            Http.Control direct=new Http.Control();direct.previewEnabled=true;final Throwable[] directError={null};Thread streaming=new Thread(()->{try{Http.complete(settings.endpoint(),"fast-stream","fixture-key",SummaryDepth.synthesize(SummaryDepth.STANDARD),new JSONObject().put("transcript","[s1] 测试字幕"),direct);}catch(Throwable e){directError[0]=e;}});streaming.start();long transportDeadline=android.os.SystemClock.uptimeMillis()+20000;while(direct.preview.isEmpty()&&streaming.isAlive()&&android.os.SystemClock.uptimeMillis()<transportDeadline)Thread.sleep(50);
            check(streaming.isAlive()&&!direct.preview.isEmpty(),"native transport previews before completion: "+direct.diagnostic()+" preview="+direct.preview+" error="+directError[0]);streaming.join(15000);check(directError[0]==null&&!streaming.isAlive(),"native preview transport completes normally");
            Activity activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settings.save("http://10.0.2.2:18765/v1","fast-stream","fixture-key");SummaryService.begin(c,"",input,true);long deadline=android.os.SystemClock.uptimeMillis()+30000;
            while(SummaryService.draft.isEmpty()&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
            check(SummaryService.running&&SummaryService.draft.contains("正在生成")&&!SummaryService.draft.contains("private-thinking"),"live draft appears before stream completes without reasoning text; running="+SummaryService.running+" draft="+SummaryService.draft+" error="+SummaryService.error+" message="+SummaryService.message);
            check(SummaryService.noteId.isEmpty()&&store.read(note.getString("id")).getLong("saved_at")==note.getLong("saved_at"),"draft never replaces the saved note");
            final boolean[] visible={false};runOnMainSync(()->{try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("statusDraft");f.setAccessible(true);TextView view=(TextView)f.get(activity);visible[0]=view!=null&&view.getVisibility()==android.view.View.VISIBLE&&view.getText().toString().contains("草稿");}catch(Exception e){throw new RuntimeException(e);}});check(visible[0],"native progress card displays draft with verification label");
            finishTask(deadline);check(SummaryService.error.isEmpty()&&SummaryService.draft.isEmpty()&&!SummaryService.noteId.isEmpty(),"completed stream saves verified note and withdraws draft");
            JSONObject complete=store.read(SummaryService.noteId);check(complete.getInt("model_steps")==1&&complete.getJSONArray("segments").length()==1077,"foreground fast pipeline remains one generation");
            settings.save("http://10.0.2.2:18765/v1","invalid-refs","fixture-key");boolean failed=false;try{new SummaryEngine(c,new Http.Control()).run("",input,true,(p,m)->{});}catch(IllegalArgumentException e){failed=true;}
            check(failed&&store.read(complete.getString("id")).getLong("saved_at")==complete.getLong("saved_at"),"unsupported fast output cannot overwrite an existing note");
            settings.save("http://10.0.2.2:18765/v1","fast-stream","fixture-key");SummaryService.begin(c,"",input,true);deadline=android.os.SystemClock.uptimeMillis()+30000;while(SummaryService.draft.isEmpty()&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
            check(SummaryService.running&&!SummaryService.draft.isEmpty(),"second active draft is cancellable");long stoppedAt=android.os.SystemClock.uptimeMillis();c.startService(new Intent(c,SummaryService.class).setAction(SummaryService.STOP));finishTask(stoppedAt+5000);
            check(SummaryService.draft.isEmpty()&&SummaryService.noteId.isEmpty()&&store.read(complete.getString("id")).getLong("saved_at")==complete.getLong("saved_at"),"cancellation withdraws draft and preserves complete note");
            code=Activity.RESULT_OK;result.putString("stream","PASS "+checks+" Android speed / preview checks\n");result.putInt("checks",checks);
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));}
        finally{for(JSONObject n:store.list())if(!before.contains(n.optString("id")))store.delete(n.optString("id"));SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> e:original.entrySet()){Object v=e.getValue();if(v instanceof String)edit.putString(e.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(e.getKey(),(Long)v);}edit.commit();}
        finish(code,result);
    }
}
