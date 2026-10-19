package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, durable task snapshots. No transcript, generated text, credentials or share URL. */
final class VideoUsageCounter {
    private final UsageCounter.Storage storage;private final UsageCounter.Sender sender;private final UsageCounter.Clock clock;
    private final String installation,version;
    private final LinkedHashMap<String,JSONObject> pending=new LinkedHashMap<>(),active=new LinkedHashMap<>();
    private long retryAt;private boolean sending;
    VideoUsageCounter(UsageCounter.Storage storage,UsageCounter.Sender sender,UsageCounter.Clock clock,String installation,String version){
        this.storage=storage;this.sender=sender;this.clock=clock;this.installation=installation;this.version=version;
        try{JSONObject state=storage.load();if(state!=null){read(state.optJSONArray("pending"),pending);read(state.optJSONArray("active"),active);retryAt=state.optLong("retry_at");}}catch(Exception ignored){}
        trim();if(retryAt>clock.now()+UsageCounter.RETRY_MS)retryAt=0;
    }
    private static void read(JSONArray rows,Map<String,JSONObject> target){if(rows==null)return;for(int i=0;i<rows.length()&&i<1000;i++)try{JSONObject e=rows.getJSONObject(i);target.put(e.getString("event_id"),e);}catch(Exception ignored){}}
    private void trim(){long oldest=java.time.LocalDate.parse(UsageCounter.day(clock.now())).minusDays(30).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();trimMap(pending,oldest);trimMap(active,oldest);}
    private void trimMap(Map<String,JSONObject> map,long oldest){map.entrySet().removeIf(e->e.getValue().optLong("started_at")<oldest||e.getValue().optLong("started_at")>clock.now()+300000);}
    private int queued(){java.util.HashSet<String> ids=new java.util.HashSet<>(pending.keySet());ids.addAll(active.keySet());return ids.size();}
    private boolean persist(){try{return storage.save(new JSONObject().put("pending",new JSONArray(new ArrayList<>(pending.values()))).put("active",new JSONArray(new ArrayList<>(active.values()))).put("retry_at",retryAt));}catch(Exception ignored){return false;}}
    synchronized String start(JSONObject source,String depth){
        try{
            trim();if(!"bili".equals(source.optString("source_kind"))||source.optInt("source_check_version")!=SourceIdentity.VERSION||!source.optString("bvid").matches("BV[0-9A-Za-z]{10}")||source.optLong("aid")<1||source.optLong("cid")<1||source.optInt("page")<1||source.optInt("page")>10000||queued()>=1000||!depth.matches("concise|standard|detailed"))return "";
            String title=source.optString("title").replaceAll("[\\s\\x00-\\x1f\\x7f]+"," ").trim();if(title.isEmpty())return "";if(title.length()>256)title=title.substring(0,256);
            String id=UUID.randomUUID().toString();JSONObject e=new JSONObject().put("event_id",id).put("started_at",clock.now()).put("completed_at",0).put("status","started").put("depth",depth).put("cached",false).put("bvid",source.getString("bvid")).put("aid",source.getLong("aid")).put("cid",source.getLong("cid")).put("page",source.getInt("page")).put("title",title);
            active.put(id,e);pending.put(id,e);if(persist())return id;active.remove(id);pending.remove(id);
        }catch(Exception ignored){}return "";
    }
    synchronized void finish(String id,int outcome,boolean cached){
        if(outcome<UsageCounter.SUCCEEDED||outcome>UsageCounter.CANCELLED)return;
        try{JSONObject original=active.get(id);if(original==null)return;JSONObject e=new JSONObject(original.toString()).put("completed_at",Math.max(clock.now(),original.getLong("started_at"))).put("status",new String[]{"started","succeeded","failed","cancelled"}[outcome]).put("cached",cached&&outcome==UsageCounter.SUCCEEDED);pending.put(id,e);active.remove(id);persist();}catch(Exception ignored){}
    }
    void flush(){
        synchronized(this){if(sending)return;sending=true;}
        try{for(int batch=0;batch<3;batch++){
            ArrayList<JSONObject> snapshots=new ArrayList<>();
            synchronized(this){trim();if(pending.isEmpty()||clock.now()<retryAt)return;for(JSONObject e:pending.values()){snapshots.add(e);if(snapshots.size()==20)break;}retryAt=clock.now()+UsageCounter.RETRY_MS;if(!persist())return;}
            try{sender.send(new JSONObject().put("schema",1).put("installation_id",installation).put("app_version",version).put("client","android").put("events",new JSONArray(snapshots)).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){return;}
            synchronized(this){for(JSONObject e:snapshots){String id=e.optString("event_id");JSONObject current=pending.get(id);if(current!=null&&current.toString().equals(e.toString()))pending.remove(id);}retryAt=0;persist();}
        }}finally{synchronized(this){sending=false;}}
    }
}
