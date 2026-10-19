package com.suikan.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

/** Native selection changes and delayed completion broadcasts on an isolated test installation. */
public final class SourceHarness extends Instrumentation {
    int checks;
    static final String A="https://www.bilibili.com/video/BV1fX4y1Q7Ux",B="https://www.bilibili.com/video/BV1tBHm6MEHi";
    void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void set(Object target,String name,Object value)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    JSONObject source(String input,String title)throws Exception{
        JSONObject s=new JSONObject().put("title",title).put("source_kind","bili").put("source_check_version",1).put("share_title_checked",true)
                .put("bvid",input.substring(input.lastIndexOf('/')+1)).put("aid",170001).put("cid",101).put("page",1).put("source_label","本地来源校验界面测试")
                .put("segments",new JSONArray().put(Json.segment(new Transcript.Segment("s1",0,2,title+"字幕首行"))).put(Json.segment(new Transcript.Segment("s2",3,4,title+"字幕末尾"))));
        s.put("transcript_hash",Json.hash(s.getJSONArray("segments").toString()));return SourceIdentity.bind(s,input,"");
    }
    interface Action {void run()throws Exception;}
    void ui(Action action)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new RuntimeException(failure[0]);}
    void completion(Context c)throws Exception{SummaryService.running=false;SummaryService.noteId="";SummaryService.subtitleReady=true;c.sendBroadcast(new Intent(SummaryService.UPDATE).setPackage(c.getPackageName()).putExtra("task_completed",true));Thread.sleep(350);waitForIdleSync();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;MainActivity main=null;Context c=getTargetContext();
        try{
            c.getSharedPreferences("settings",0).edit().clear().commit();SecureSettings settings=new SecureSettings(c);settings.lastInput(A);
            SubtitleStore subtitles=new SubtitleStore(c);JSONObject a=source(A,"视频 A"),b=source(B,"视频 B");subtitles.save(a);
            main=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity current=main;
            check(field(current,"currentSubtitle")!=null,"matching stored subtitle restored");
            check(((LinearLayout)field(current,"resultArea")).getChildCount()>0,"full subtitle card displayed");
            ui(()->{set(current,"imported","旧视频的导入文稿");set(current,"currentNote",new JSONObject().put("title","旧笔记"));((EditText)field(current,"linkField")).setText(B);});
            check(field(current,"imported").equals(""),"native text edit clears old import");
            check(field(current,"currentSubtitle")==null&&field(current,"currentNote")==null,"native text edit clears old results");
            check(((LinearLayout)field(current,"resultArea")).getChildCount()==0,"stale source text immediately removed from view");
            check(settings.lastInput().equals(B),"new input persisted for overlay");
            subtitles.save(a);completion(c);check(field(current,"currentSubtitle")==null,"delayed A completion cannot appear under B link");
            ui(()->current.onNewIntent(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,A)));
            subtitles.save(a);c.sendBroadcast(new Intent(SummaryService.UPDATE).setPackage(c.getPackageName()).putExtra("overlay_state",true));Thread.sleep(350);waitForIdleSync();check(field(current,"currentSubtitle")==null,"overlay state update cannot replay an earlier completion");
            subtitles.save(b);completion(c);check(field(current,"currentSubtitle")==null,"new share rejects previous B completion");
            subtitles.save(a);completion(c);JSONObject shown=(JSONObject)field(current,"currentSubtitle");
            check(shown!=null&&shown.getString("title").equals("视频 A"),"matching completion restores correct source");
            check(SubtitleFiles.text(shown,false).contains("视频 A字幕末尾")&&!SubtitleFiles.text(shown,false).contains("视频 B"),"correct source includes tail without mixing videos");
            check(SourceIdentity.description(shown).contains("分享标题已核对"),"source verification visible");
            ui(()->{set(current,"imported","旧文稿");((EditText)field(current,"linkField")).setText(B);});
            JSONObject legacy=new JSONObject(b.toString());legacy.remove("input_hash");subtitles.save(legacy);completion(c);
            check(field(current,"currentSubtitle")==null,"unbound legacy completion cannot replace current video");
            ui(()->current.finish());main=null;
            main=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(field(main,"currentSubtitle")==null,"cold activity start rejects unbound legacy subtitle");
            code=Activity.RESULT_OK;result.putString("stream","PASS "+checks+" Android source selection checks\n");result.putInt("checks",checks);
        }catch(Throwable e){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(e));}
        finally{SummaryService.subtitleReady=false;SummaryService.noteId="";if(main!=null){final MainActivity last=main;runOnMainSync(()->last.finish());}}
        finish(code,result);
    }
}
