package com.suikan.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;

/** Local TLS fixture only; its test CA never enters the production APK. */
public final class UsageHarness extends Instrumentation {
    int checks;boolean configured;
    void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);configured="enabled".equals(args.getString("mode"));start();}
    View find(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View child=find(g.getChildAt(i),label);if(child!=null)return child;}}return null;}
    JSONObject api(String path,JSONObject body)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://localhost:18768"+path).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer local-test-statistics-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
        try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[512];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);return new JSONObject(bytes.toString("UTF-8"));}finally{c.disconnect();}
    }
    void expire(UsageAnalytics analytics)throws Exception{java.lang.reflect.Field f=UsageAnalytics.class.getDeclaredField("access");f.setAccessible(true);Object access=f.get(analytics);java.lang.reflect.Field r=AccessPolicy.class.getDeclaredField("retryAt");r.setAccessible(true);r.setLong(access,0);}
    void flushUsage(UsageAnalytics analytics)throws Exception{java.lang.reflect.Field f=UsageAnalytics.class.getDeclaredField("worker");f.setAccessible(true);((java.util.concurrent.ExecutorService)f.get(analytics)).submit(()->{}).get(20,java.util.concurrent.TimeUnit.SECONDS);}
    void startTask(Context context,boolean summarize,boolean denied)throws Exception{
        SummaryService.error="";SummaryService.subtitleReady=false;SummaryService.noteId="";SummaryService.message="";
        runOnMainSync(()->{try{if(summarize)SummaryService.begin(context,"","测试字幕第一句。测试字幕第二句。",false);else SummaryService.extract(context,"","恢复后可以提取的完整字幕。第二句内容。第三句内容。");}catch(Exception e){throw new RuntimeException(e);}});
        long until=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<until){if(!SummaryService.running&&(denied?!SummaryService.error.isEmpty():(summarize?!SummaryService.noteId.isEmpty():SummaryService.subtitleReady)))return;Thread.sleep(150);}
        throw new AssertionError("task did not finish: "+SummaryService.message+" / "+SummaryService.error);
    }
    @Override public void onStart(){
        Bundle output=new Bundle();int result=Activity.RESULT_CANCELED;Activity activity=null;String noteId="";
        SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        try{
            Context context=getTargetContext();SharedPreferences prefs=context.getSharedPreferences("usage_statistics",0);prefs.edit().clear().commit();
            java.lang.reflect.Field singleton=UsageAnalytics.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
            if(configured){
                KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType());ks.load(null,null);try(InputStream cert=getContext().getAssets().open("usage-test-ca.crt")){ks.setCertificateEntry("local-test",CertificateFactory.getInstance("X.509").generateCertificate(cert));}
                TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
                check(api("/api/summary",null).getInt("total_installations")==0,"TLS fixture starts empty");
            }
            UsageAnalytics analytics=UsageAnalytics.get(context);check(analytics.configured()==configured,"build configuration");check(analytics.enabled()==configured,"configured collection enabled without consent");
            activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1200);final Activity main=activity;
            check(find(main.getWindow().getDecorView(),"同意参与")==null&&find(main.getWindow().getDecorView(),"暂不参与")==null,"no collection consent UI");
            runOnMainSync(()->find(main.getWindow().getDecorView(),"设置").performClick());check(find(main.getWindow().getDecorView(),"参与使用统计")==null,"no consent switch");
            if(!configured){
                check(!prefs.contains("state"),"unconfigured production build creates no identifier");analytics.requireAccess();check(true,"unconfigured app remains usable");
                check(find(main.getWindow().getDecorView(),"复制安装编号")==null,"no unusable installation code in unconfigured build");
            }else{
                check(find(main.getWindow().getDecorView(),"复制安装编号")!=null,"installation code available for administrator lookup");
                long deadline=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<deadline&&new JSONObject(prefs.getString("state","{}")).optString("ack_day").isEmpty())Thread.sleep(150);
                check(!new JSONObject(prefs.getString("state","{}")).optString("ack_day").isEmpty(),"automatic Android TLS reporting acknowledged");
                JSONObject s=api("/api/summary",null);check(s.getInt("total_installations")==1&&s.getInt("dau")==1,"default-on deduplicated reporting");check(s.getJSONArray("versions").getJSONObject(0).getString("version").equals("0.1.11"),"package version reported");
                for(int i=0;i<5;i++)analytics.foreground();Thread.sleep(500);check(api("/api/summary",null).getInt("total_installations")==1,"repeat foreground deduplicates");
                String code=analytics.installationCode();JSONObject entry=api("/api/installations?q="+code,null).getJSONArray("items").getJSONObject(0);check(entry.getString("id").equals(code)&&entry.getBoolean("enabled"),"native installation code matches server hash");
                JSONObject note=new JSONObject().put("title","保留笔记").put("transcript_hash","instrumentation-access-test").put("summary",new JSONObject().put("overview","停用也能导出的已有笔记"));LocalStore store=new LocalStore(context);store.save(note);noteId=note.getString("id");
                JSONObject source=VideoSource.read("","原来保存的完整字幕。",new Http.Control(),(p,t)->{});new SubtitleStore(context).save(source);
                api("/api/access",new JSONObject().put("id",code).put("enabled",false).put("reason","本机测试停用"));expire(analytics);
                try{analytics.requireAccess();throw new AssertionError("denial missing");}catch(IllegalStateException e){check(e.getMessage().contains("本机测试停用"),"real server denial applied");}
                check(!new JSONObject(prefs.getString("access_state","{}")).getBoolean("allowed"),"denial persisted on device");
                startTask(context,false,true);check(SummaryService.error.contains("本机测试停用"),"extraction blocked by service");check(new SubtitleStore(context).read().getString("transcript_hash").equals(source.getString("transcript_hash")),"denied extraction preserves previous subtitles");
                startTask(context,true,true);check(SummaryService.error.contains("本机测试停用"),"summary blocked before model request");
                flushUsage(analytics);check(api("/api/summary",null).getJSONObject("operations").getJSONObject("total").getLong("started")==0,"denied tasks and foreground checks do not count as use");
                check(LocalStore.markdown(store.read(noteId)).contains("停用也能导出"),"existing note readable and exportable");
                api("/api/access",new JSONObject().put("id",code).put("enabled",true).put("reason",""));expire(analytics);analytics.requireAccess();check(new JSONObject(prefs.getString("access_state","{}")).getBoolean("allowed"),"real server reenable persisted");
                startTask(context,false,false);check(SummaryService.subtitleReady&&SummaryService.error.isEmpty(),"restored extraction succeeds");check(SubtitleFiles.text(new SubtitleStore(context).read(),false).contains("恢复后可以提取"),"restored task saves full content");
                flushUsage(analytics);JSONObject totals=api("/api/summary",null).getJSONObject("operations").getJSONObject("total");check(totals.getJSONObject("extract").getInt("started")==1&&totals.getJSONObject("extract").getInt("succeeded")==1,"real extraction start and success reported once");
                SecureSettings settings=new SecureSettings(context);settings.save("http://10.0.2.2:18765/v1","auth-error","fixture-key");startTask(context,true,true);check(!SummaryService.error.isEmpty(),"model failure completes service task");
                settings.save("http://10.0.2.2:18765/v1","depth-fixture","fixture-key");startTask(context,true,false);check(!SummaryService.noteId.isEmpty(),"model fixture summary succeeds");String summaryId=SummaryService.noteId;
                startTask(context,true,false);check(SummaryService.noteId.equals(summaryId),"reading cached summary is one additional use");
                settings.save("http://10.0.2.2:18765/v1","delayed-model","fixture-key");SummaryService.message="";SummaryService.begin(context,"","取消任务的字幕内容。",true);
                long cancelUntil=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<cancelUntil&&(!SummaryService.running||!SummaryService.modelWaiting))Thread.sleep(100);
                check(SummaryService.running&&SummaryService.modelWaiting,"cancellation task reaches model request");SummaryService.extract(context,"","忙碌时的第二次点击");Thread.sleep(300);context.startService(new Intent(context,SummaryService.class).setAction(SummaryService.STOP));
                cancelUntil=System.currentTimeMillis()+20000;while(SummaryService.running&&System.currentTimeMillis()<cancelUntil)Thread.sleep(100);check(!SummaryService.running&&SummaryService.message.contains("已停止"),"service cancellation completes");
                flushUsage(analytics);totals=api("/api/summary",null).getJSONObject("operations").getJSONObject("total");JSONObject summaries=totals.getJSONObject("summary");
                check(totals.getInt("started")==5&&summaries.getInt("started")==4,"busy duplicate click does not inflate task count");
                check(summaries.getInt("succeeded")==2&&summaries.getInt("failed")==1&&summaries.getInt("cancelled")==1,"real summary success, failure and cancellation reach backend");
                settings.save("http://10.0.2.2:18765/v1","echo","fixture-key");SummaryService.message="";SummaryService.probe(context);long probeUntil=System.currentTimeMillis()+20000;
                while(System.currentTimeMillis()<probeUntil&&(SummaryService.running||!SummaryService.message.contains("连接测试通过")))Thread.sleep(100);
                check(SummaryService.message.contains("连接测试通过"),"model probe completed");flushUsage(analytics);check(api("/api/summary",null).getJSONObject("operations").getJSONObject("total").getInt("started")==5,"model probe does not count as use");
                check(api("/api/installations?q="+code,null).getJSONArray("items").getJSONObject(0).getJSONObject("usage").getInt("started")==5,"installation usage matches native tasks");new LocalStore(context).delete(summaryId);
            }
            check(context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName.equals("0.1.11"),"installed version correct");
            result=Activity.RESULT_OK;output.putString("stream","PASS "+checks+" Android usage/access checks ("+(configured?"configured TLS fixture":"unconfigured production APK")+")\n");
        }catch(Throwable error){output.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));}
        finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}if(!noteId.isEmpty())new LocalStore(getTargetContext()).delete(noteId);}
        finish(result,output);
    }
}
