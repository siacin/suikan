package com.suikan.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Tests the installed production APK. Local fixture only, no real account credentials. */
public final class FeatureHarness extends Instrumentation {
    int checks;
    void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void set(Object target,String name,Object value)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    void call(Object target,String name,Class<?>[] types,Object...args)throws Exception{java.lang.reflect.Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
    void waitTask()throws Exception{long end=android.os.SystemClock.uptimeMillis()+60000;while(!SummaryService.running&&SummaryService.message.isEmpty()&&android.os.SystemClock.uptimeMillis()<end)Thread.sleep(50);while(SummaryService.running&&android.os.SystemClock.uptimeMillis()<end)Thread.sleep(50);Thread.sleep(300);check(!SummaryService.running&&SummaryService.error.isEmpty(),"foreground task completed: "+SummaryService.error);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Context c=getTargetContext();LocalStore store=new LocalStore(c);SubtitleStore subtitles=new SubtitleStore(c);JSONObject oldSubtitle=subtitles.read();SharedPreferences prefs=c.getSharedPreferences("settings",0);Map<String,?> oldPrefs=new HashMap<>(prefs.getAll());Set<String> oldNotes=new HashSet<>();for(JSONObject n:store.list())oldNotes.add(n.optString("id"));Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;Activity activity=null;
        try{
            prefs.edit().clear().commit();SecureSettings settings=new SecureSettings(c);check(settings.summaryDepth().equals(SummaryDepth.STANDARD),"new default standard without API configuration");
            String srt="1\n00:00:01,125 --> 00:00:03,500\n字幕首行\n第二行\n\n2\n00:00:05,050 --> 00:00:06,250\n完整末尾字幕\n";
            JSONObject source=VideoSource.read("",srt,new Http.Control(),(p,m)->{});check(source.getJSONArray("segments").length()==2&&!source.has("summary"),"subtitle acquisition works with no model settings");
            SummaryService.message="";SummaryService.extract(c,"",srt);Thread.sleep(200);waitTask();check(SummaryService.subtitleReady&&SummaryService.percent==100&&subtitles.read().getJSONArray("segments").toString().contains("完整末尾字幕"),"extraction persists full source without model");check(store.list().size()==oldNotes.size(),"extract only creates no summary note");
            settings.save("http://10.0.2.2:18765/v1","depth-fixture","fixture-key");StringBuilder imported=new StringBuilder();for(int i=0;i<15;i++){imported.append("[00:10] ");for(int j=0;j<600;j++)imported.append("重要知识与条件");imported.append(i==14?"视频末尾完整内容":"本段内容").append('\n');}
            JSONObject concise=new SummaryEngine(c,new Http.Control()).run("",imported.toString(),true,SummaryDepth.CONCISE,(p,m)->{});
            JSONObject standard=new SummaryEngine(c,new Http.Control()).run("",imported.toString(),true,SummaryDepth.STANDARD,(p,m)->{});
            JSONObject detailed=new SummaryEngine(c,new Http.Control()).run("",imported.toString(),true,SummaryDepth.DETAILED,(p,m)->{});
            check(concise.getJSONObject("summary").getJSONArray("key_points").length()==2&&standard.getJSONObject("summary").getJSONArray("key_points").length()==5,"model receives selected concise and standard prompts");
            check(!concise.getString("id").equals(standard.getString("id"))&&!standard.getString("id").equals(detailed.getString("id")),"three depth notes coexist");
            check(store.read(detailed.getString("id")).getJSONArray("segments").toString().contains("视频末尾完整内容"),"all modes preserve full source");
            JSONObject cached=new SummaryEngine(c,new Http.Control()).run("",imported.toString(),false,SummaryDepth.CONCISE,(p,m)->{});check(cached.getLong("saved_at")==concise.getLong("saved_at"),"selected depth reuses only its own cache");
            settings.summaryDepth(SummaryDepth.CONCISE);SummaryService.message="";SummaryService.begin(c,"",imported.toString(),true);settings.summaryDepth(SummaryDepth.DETAILED);Thread.sleep(200);waitTask();check(store.read(SummaryService.noteId).getString("summary_depth").equals(SummaryDepth.CONCISE),"task captures depth before later preference changes");
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity main=(MainActivity)activity;
            runOnMainSync(()->{RadioButton option=main.findViewById(101);option.performClick();});check(settings.summaryDepth().equals(SummaryDepth.STANDARD),"native standard selector persists preference");
            final Throwable[] uiError={null};runOnMainSync(()->{try{set(main,"currentSubtitle",source);call(main,"copySubtitles",new Class<?>[]{boolean.class},false);}catch(Throwable e){uiError[0]=e;}});if(uiError[0]!=null)throw new RuntimeException(uiError[0]);
            ClipboardManager clipboard=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);check(clipboard.getPrimaryClip().getItemAt(0).getText().toString().equals(SubtitleFiles.text(source,false)),"Android clipboard contains full plain subtitle");
            runOnMainSync(()->{try{call(main,"copySubtitles",new Class<?>[]{boolean.class},true);}catch(Throwable e){uiError[0]=e;}});if(uiError[0]!=null)throw new RuntimeException(uiError[0]);check(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("[00:05] 完整末尾字幕"),"Android clipboard contains real timestamp and tail");
            File output=new File(c.getCacheDir(),"feature-export.srt");IntentFilter picker=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.addDataType("application/x-subrip");ActivityMonitor monitor=addMonitor(picker,new ActivityResult(Activity.RESULT_CANCELED,null),true);
            runOnMainSync(()->{try{call(main,"saveDocument",new Class<?>[]{String.class,String.class,String.class},SubtitleFiles.srt(source),"application/x-subrip","测试字幕.srt");}catch(Throwable e){uiError[0]=e;}});if(uiError[0]!=null)throw new RuntimeException(uiError[0]);
            check(monitor.getHits()==1,"save uses system create-document picker");removeMonitor(monitor);
            final Bundle state=new Bundle();runOnMainSync(()->{try{set(main,"imported",srt);call(main,"onSaveInstanceState",new Class<?>[]{Bundle.class},state);}catch(Throwable e){uiError[0]=e;}});if(uiError[0]!=null)throw new RuntimeException(uiError[0]);
            check(state.getBoolean("has_imported")&&!state.containsKey("transcript"),"activity state stores no large transcript in Binder");
            // Simulate activity recreation: pending bytes are read from the private file, not an old field.
            runOnMainSync(()->{main.onActivityResult(22,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(output)));});
            String saved=new String(java.nio.file.Files.readAllBytes(output.toPath()),StandardCharsets.UTF_8);check(saved.equals(SubtitleFiles.srt(source))&&saved.contains("00:00:01,125"),"ContentResolver saves full UTF-8 SRT and milliseconds");output.delete();
            runOnMainSync(()->main.recreate());Thread.sleep(2000);check(subtitles.read()!=null&&settings.summaryDepth().equals(SummaryDepth.STANDARD),"subtitle and selected depth survive activity recreation");
            code=Activity.RESULT_OK;result.putString("stream","PASS "+checks+" Android subtitle / depth checks\n");result.putInt("checks",checks);
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));}
        finally{
            for(JSONObject n:store.list())if(!oldNotes.contains(n.optString("id")))store.delete(n.optString("id"));
            try{if(oldSubtitle!=null)subtitles.save(oldSubtitle);else new android.util.AtomicFile(new File(c.getFilesDir(),"last-subtitle.json")).delete();}catch(Exception ignored){}
            for(String name:new String[]{"pending-export.txt","draft-transcript.txt","feature-export.srt"})new android.util.AtomicFile(new File(c.getCacheDir(),name)).delete();
            SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> e:oldPrefs.entrySet()){Object v=e.getValue();if(v instanceof String)edit.putString(e.getKey(),(String)v);else if(v instanceof Integer)edit.putInt(e.getKey(),(Integer)v);else if(v instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Long)edit.putLong(e.getKey(),(Long)v);}edit.commit();
        }
        finish(code,result);
    }
}
