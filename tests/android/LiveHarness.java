package com.suikan.app;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.CookieManager;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Optional real test. Runtime secrets are provided via private cache, never compiled into APKs. */
public final class LiveHarness extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){
  Context c=getTargetContext();Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
  SharedPreferences prefs=c.getSharedPreferences("settings",Context.MODE_PRIVATE);Map<String,?> original=new HashMap<>(prefs.getAll());
  java.net.ProxySelector originalProxy=java.net.ProxySelector.getDefault();
  File configFile=new File(c.getCacheDir(),"live-config.json");String key="";
  try {
   JSONObject config=new JSONObject(new String(java.nio.file.Files.readAllBytes(configFile.toPath()),StandardCharsets.UTF_8));key=config.getString("key");
   if(config.has("proxy_host"))java.net.ProxySelector.setDefault(new java.net.ProxySelector(){
    public java.util.List<java.net.Proxy> select(java.net.URI uri){
     if(java.net.URI.create(config.optString("endpoint")).getHost().equals(uri.getHost()))return Collections.singletonList(new java.net.Proxy(java.net.Proxy.Type.HTTP,new java.net.InetSocketAddress(config.optString("proxy_host"),config.optInt("proxy_port"))));
     return Collections.singletonList(java.net.Proxy.NO_PROXY);
    }
    public void connectFailed(java.net.URI uri,java.net.SocketAddress address,IOException error){}
   });
   new SecureSettings(c).save(config.getString("endpoint"),config.getString("model"),key,0,true);
   runOnMainSync(()->{CookieManager cm=CookieManager.getInstance();cm.setCookie("https://www.bilibili.com/",config.optString("cookies"));cm.flush();});
   // Each cookie must be scoped to the parent domain to reach the API subdomain.
   for(String cookie:config.optString("cookies").split("; "))runOnMainSync(()->CookieManager.getInstance().setCookie("https://www.bilibili.com/",cookie+"; Domain=.bilibili.com; Path=/; Secure"));
   runOnMainSync(()->CookieManager.getInstance().flush());Thread.sleep(1000);
   Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivitySync(open);
   SummaryService.begin(c,config.getString("input"),"",true);
   long started=android.os.SystemClock.elapsedRealtime(),deadline=started+5400000;boolean observed=false;String previous="";
   while(android.os.SystemClock.elapsedRealtime()<deadline){
    if(SummaryService.running)observed=true;
    if(!SummaryService.message.equals(previous)){previous=SummaryService.message;Bundle status=new Bundle();status.putString("stream","STAGE "+previous+"\n");sendStatus(0,status);}
    if(observed&&!SummaryService.running)break;Thread.sleep(500);
   }
   if(SummaryService.running||!observed)throw new AssertionError("Service did not finish");
   if(!SummaryService.error.isEmpty())throw new IllegalArgumentException(SummaryService.error);
   JSONObject note=new LocalStore(c).read(SummaryService.noteId);JSONArray segments=note.getJSONArray("segments");
   if(!config.getString("expected_bvid").equals(note.getString("bvid")))throw new AssertionError("Wrong video");
   if(segments.length()!=config.getInt("expected_rows"))throw new AssertionError("Source segments incomplete");
   if(!segments.getJSONObject(segments.length()-1).getString("text").equals(config.getString("expected_last_text")))throw new AssertionError("Last source segment missing");
   if(note.getJSONArray("points").length()==0||note.getJSONObject("summary").getJSONArray("chapters").length()==0)throw new AssertionError("Detailed result missing");
   String md=LocalStore.markdown(note);if(md.contains(key)||md.contains("SESSDATA="))throw new AssertionError("Export contains credentials");
   try(FileOutputStream out=new FileOutputStream(new File(c.getCacheDir(),"live-result.json"))){out.write(note.toString(2).getBytes(StandardCharsets.UTF_8));}
   try(FileOutputStream out=new FileOutputStream(new File(c.getCacheDir(),"live-result.md"))){out.write(md.getBytes(StandardCharsets.UTF_8));}
   Intent show=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("note_id",note.getString("id"));c.startActivity(show);
   result.putString("stream","PASS real Android video + subtitles + model + foreground service + storage + Markdown; rows="+segments.length()+" points="+note.getJSONArray("points").length()+" seconds="+(android.os.SystemClock.elapsedRealtime()-started)/1000+"\n");code=Activity.RESULT_OK;
  }catch(Throwable e){result.putString("stream","FAIL real Android test: "+Http.redact(e.getMessage(),key)+"\n");}
  finally{
   java.net.ProxySelector.setDefault(originalProxy);
   configFile.delete();SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){Object value=entry.getValue();if(value instanceof String)edit.putString(entry.getKey(),(String)value);else if(value instanceof Integer)edit.putInt(entry.getKey(),(Integer)value);else if(value instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Long)edit.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(entry.getKey(),(Float)value);}edit.commit();
   runOnMainSync(()->CookieManager.getInstance().removeAllCookies(ok->CookieManager.getInstance().flush()));
  }
  finish(code,result);
 }
}
