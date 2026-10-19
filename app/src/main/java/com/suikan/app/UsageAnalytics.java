package com.suikan.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UsageAnalytics {
    private static UsageAnalytics instance;
    private final UsageCounter counter;
    private final AccessPolicy access;
    private final VideoUsageCounter videos;
    private final String endpoint;
    private final String version;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"usage-count");t.setDaemon(true);return t;});
    static synchronized UsageAnalytics get(Context context){if(instance==null)instance=new UsageAnalytics(context.getApplicationContext());return instance;}
    private UsageAnalytics(Context context){
        endpoint=readEndpoint(context);
        SharedPreferences prefs=context.getSharedPreferences("usage_statistics",Context.MODE_PRIVATE);
        String currentVersion="0";try{currentVersion=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}version=currentVersion;
        // An unconfigured package must not create an installation identifier or submit requests.
        if(endpoint.isEmpty()){counter=null;access=null;videos=null;return;}
        counter=new UsageCounter(new UsageCounter.Storage(){
            public JSONObject load(){try{return new JSONObject(prefs.getString("state","{}"));}catch(Exception e){return new JSONObject();}}
            public boolean save(JSONObject value){return prefs.edit().putString("state",value.toString()).commit();}
        },this::post,System::currentTimeMillis,version);
        videos=new VideoUsageCounter(new UsageCounter.Storage(){
            public JSONObject load(){try{return new JSONObject(prefs.getString("video_state","{}"));}catch(Exception e){return new JSONObject();}}
            public boolean save(JSONObject value){return prefs.edit().putString("video_state",value.toString()).commit();}
        },this::postVideo,System::currentTimeMillis,counter.identifier(),version);
        access=new AccessPolicy(new UsageCounter.Storage(){
            public JSONObject load(){try{return new JSONObject(prefs.getString("access_state","{}"));}catch(Exception e){return new JSONObject();}}
            public boolean save(JSONObject value){return prefs.edit().putString("access_state",value.toString()).commit();}
        },System::currentTimeMillis,this::fetchAccess,counter.identifier());
    }
    private static String readEndpoint(Context c){
        try(InputStream in=c.getAssets().open("usage-statistics.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[512];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>4096)return "";bytes.write(buffer,0,n);}
            String value=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).optString("endpoint").trim();
            if(value.isEmpty())return "";
            URL url=new URL(value);
            if(!"https".equals(url.getProtocol())||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null||!url.getPath().equals("/v1/usage"))return "";
            return value;
        }catch(Exception ignored){return "";}
    }
    boolean configured(){return !endpoint.isEmpty();}
    boolean enabled(){return configured()&&counter.enabled();}
    String installationCode(){
        if(!enabled())return "";
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(("suikan-usage-v1:"+counter.identifier()).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(Exception e){return "";}
    }
    void foreground(){if(!enabled())return;worker.execute(()->{access.refresh();counter.foreground();counter.flush();videos.flush();});}
    String videoStarted(JSONObject source,String depth){if(!enabled())return "";try{String id=videos.start(source,depth);worker.execute(videos::flush);return id;}catch(Exception ignored){return "";}}
    void videoFinished(String id,int outcome,boolean cached){if(!enabled()||id.isEmpty())return;try{videos.finish(id,outcome,cached);worker.execute(videos::flush);}catch(Exception ignored){}}
    /** Counting is independent of the task result and never waits for the network. */
    void operation(boolean extract,int outcome){
        if(!enabled())return;
        try{counter.operation(extract,outcome);worker.execute(counter::flush);}catch(Exception ignored){}
    }
    /** Every task entry, including the overlay, is checked in the service worker before video/model I/O. */
    void requireAccess(){
        if(!configured())return;
        if(!enabled())throw new IllegalStateException("无法保存安装状态，请检查手机存储后重试");
        access.refresh();access.requireAllowed();foreground();
    }
    private HttpURLConnection request(String address,byte[] bytes,int timeout)throws Exception{
        HttpURLConnection request=(HttpURLConnection)new URL(address).openConnection();
        request.setConnectTimeout(timeout);request.setReadTimeout(timeout);request.setInstanceFollowRedirects(false);
        request.setRequestMethod("POST");request.setDoOutput(true);request.setUseCaches(false);
        request.setRequestProperty("Content-Type","application/json; charset=utf-8");request.setRequestProperty("User-Agent","SuiKan-Usage/3");
        request.setFixedLengthStreamingMode(bytes.length);return request;
    }
    private JSONObject fetchAccess()throws Exception{
        byte[] bytes=new JSONObject().put("schema",1).put("installation_id",counter.identifier()).put("app_version",version).toString().getBytes(StandardCharsets.UTF_8);
        String address=endpoint.substring(0,endpoint.length()-"/v1/usage".length())+"/v1/access";
        HttpURLConnection request=request(address,bytes,3000);
        try{
            try(OutputStream out=request.getOutputStream()){out.write(bytes);}
            if(request.getResponseCode()!=200)throw new java.io.IOException("Access service unavailable");
            try(InputStream in=request.getInputStream();ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                byte[] chunk=new byte[512];int n;while((n=in.read(chunk))!=-1){if(buffer.size()+n>4096)throw new java.io.IOException("Invalid access response");buffer.write(chunk,0,n);}
                return new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{request.disconnect();}
    }
    private void post(byte[] bytes)throws Exception{
        postTo(endpoint,bytes);
    }
    private void postVideo(byte[] bytes)throws Exception{postTo(endpoint.substring(0,endpoint.length()-"/v1/usage".length())+"/v1/video-usage",bytes);}
    private void postTo(String address,byte[] bytes)throws Exception{
        HttpURLConnection request=request(address,bytes,5000);
        try{
            if(!counter.enabled())return;
            try(OutputStream out=request.getOutputStream()){out.write(bytes);}
            if(request.getResponseCode()!=204)throw new java.io.IOException("Usage service unavailable");
        }finally{request.disconnect();}
    }
}
