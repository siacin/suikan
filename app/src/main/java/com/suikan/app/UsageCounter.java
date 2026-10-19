package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Anonymous daily activity and cumulative operation snapshots; no video content. */
final class UsageCounter {
    interface Storage { JSONObject load(); boolean save(JSONObject value); }
    interface Sender { void send(byte[] payload) throws Exception; }
    interface Clock { long now(); }
    static final int STARTED=0,SUCCEEDED=1,FAILED=2,CANCELLED=3;
    static final long RETRY_MS=15*60*1000L,MAX_COUNT=1000000000L;
    private final Storage storage;
    private final Sender sender;
    private final Clock clock;
    private final String version;
    private final TreeSet<String> pending=new TreeSet<>();
    private final TreeMap<String,long[]> operations=new TreeMap<>(),acknowledged=new TreeMap<>();
    private String id="",ackDay="";
    private long retryAt;
    private boolean sending,ready;

    UsageCounter(Storage storage,Sender sender,Clock clock,String version){
        this.storage=storage;this.sender=sender;this.clock=clock;this.version=version;
        JSONObject value=storage.load();
        if(value!=null){
            id=value.optString("id");ackDay=value.optString("ack_day");retryAt=value.optLong("retry_at");
            if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))id="";
            JSONArray days=value.optJSONArray("pending");if(days!=null)for(int i=0;i<days.length();i++)try{pending.add(LocalDate.parse(days.getString(i)).toString());}catch(Exception ignored){}
            JSONArray records=value.optJSONArray("operations");if(records!=null)for(int i=0;i<records.length();i++)try{
                JSONObject record=records.getJSONObject(i);String date=LocalDate.parse(record.getString("day")).toString();
                long[] counts=readCounts(record.getJSONArray("counts")),ack=readCounts(record.getJSONArray("ack"));
                for(int j=0;j<8;j++)ack[j]=Math.min(ack[j],counts[j]);
                operations.put(date,counts);acknowledged.put(date,ack);
            }catch(Exception ignored){}
        }
        if(id.isEmpty()){id=UUID.randomUUID().toString();ackDay="";pending.clear();operations.clear();acknowledged.clear();}
        trim();
        // A bad wall clock must not suppress retries indefinitely after it is corrected.
        if(retryAt>clock.now()+RETRY_MS)retryAt=0;
        ready=persist();
    }
    private static long[] readCounts(JSONArray array)throws Exception{
        if(array.length()!=8)throw new IllegalArgumentException();long[] out=new long[8];
        for(int i=0;i<8;i++){Object n=array.get(i);if(!(n instanceof Number))throw new IllegalArgumentException();out[i]=((Number)n).longValue();if(out[i]<0||out[i]>MAX_COUNT||((Number)n).doubleValue()!=out[i])throw new IllegalArgumentException();}
        return out;
    }
    private static JSONArray counts(long[] value,int offset,int size){JSONArray out=new JSONArray();for(int i=offset;i<offset+size;i++)out.put(value[i]);return out;}
    static String day(long millis){return Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();}
    synchronized boolean enabled(){return ready&&!id.isEmpty();}
    synchronized String identifier(){return enabled()?id:"";}
    synchronized void foreground(){
        if(!enabled())return;
        int before=pending.size();trim();String today=day(clock.now());
        boolean added=!today.equals(ackDay)&&pending.add(today);
        if(added||before!=pending.size())persist();
    }
    /** Called once at accepted task start and once for its terminal outcome. */
    synchronized void operation(boolean extract,int outcome){
        if(!enabled()||outcome<STARTED||outcome>CANCELLED)return;
        trim();String today=day(clock.now());long[] value=operations.get(today);
        if(value==null){value=new long[8];operations.put(today,value);acknowledged.put(today,new long[8]);}
        int index=(extract?0:4)+outcome;if(value[index]<MAX_COUNT)value[index]++;
        if(!today.equals(ackDay))pending.add(today);
        persist();
    }
    private void trim(){
        String today=day(clock.now()),oldest=LocalDate.parse(today).minusDays(30).toString();
        pending.removeIf(d->d.compareTo(oldest)<0||d.compareTo(today)>0);
        operations.keySet().removeIf(d->d.compareTo(oldest)<0||d.compareTo(today)>0);
        acknowledged.keySet().retainAll(operations.keySet());
        while(pending.size()>31)pending.pollFirst();
    }
    private boolean persist(){
        try{
            JSONArray records=new JSONArray();
            for(Map.Entry<String,long[]> e:operations.entrySet())records.put(new JSONObject().put("day",e.getKey()).put("counts",counts(e.getValue(),0,8)).put("ack",counts(acknowledged.get(e.getKey()),0,8)));
            return storage.save(new JSONObject().put("id",id).put("ack_day",ackDay)
                .put("retry_at",retryAt).put("pending",new JSONArray(new ArrayList<>(pending))).put("operations",records));}
        catch(Exception ignored){return false;}
    }
    /** Called off the UI thread. Idempotent on the server, including lost acknowledgments. */
    void flush(){
        ArrayList<String> days;String install;TreeMap<String,long[]> snapshots=new TreeMap<>();
        synchronized(this){
            trim();if(!enabled()||sending||clock.now()<retryAt)return;
            for(Map.Entry<String,long[]> e:operations.entrySet())if(!Arrays.equals(e.getValue(),acknowledged.get(e.getKey())))snapshots.put(e.getKey(),e.getValue().clone());
            if(pending.isEmpty()&&snapshots.isEmpty())return;
            days=new ArrayList<>(pending);install=id;sending=true;retryAt=clock.now()+RETRY_MS;
            if(!persist()){sending=false;return;}
        }
        boolean success=false;
        try{
            JSONArray records=new JSONArray();
            for(Map.Entry<String,long[]> e:snapshots.entrySet())records.put(new JSONObject().put("day",e.getKey()).put("extract",counts(e.getValue(),0,4)).put("summary",counts(e.getValue(),4,4)));
            byte[] payload=new JSONObject().put("schema",2).put("installation_id",install)
                    .put("app_version",version).put("active_days",new JSONArray(days)).put("operation_days",records).toString().getBytes(StandardCharsets.UTF_8);
            sender.send(payload);success=true;
        }catch(Exception ignored){/* Keep the bounded queue; never affect video tasks or log payloads. */}
        finally{synchronized(this){
            sending=false;
            if(enabled()&&id.equals(install)){
                if(success){
                    pending.removeAll(days);if(!days.isEmpty())ackDay=days.get(days.size()-1);
                    for(Map.Entry<String,long[]> e:snapshots.entrySet())if(operations.containsKey(e.getKey()))acknowledged.put(e.getKey(),e.getValue());
                    retryAt=0;
                }
                trim();
                persist();
            }
        }}
    }
}
