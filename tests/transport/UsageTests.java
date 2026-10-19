package com.suikan.app;

import org.json.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Production logic with fake clock/storage/transport; no external network. */
public final class UsageTests {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Memory implements UsageCounter.Storage {
        JSONObject value=new JSONObject();boolean fail;
        public JSONObject load(){try{return new JSONObject(value.toString());}catch(Exception e){throw new RuntimeException(e);}}
        public boolean save(JSONObject v){if(fail)return false;try{value=new JSONObject(v.toString());return true;}catch(Exception e){return false;}}
    }
    static long now=Instant.parse("2026-10-09T12:00:00Z").toEpochMilli();
    static JSONObject last;static int calls;static boolean fail;
    static UsageCounter counter(Memory store){return new UsageCounter(store,bytes->{calls++;last=new JSONObject(new String(bytes,"UTF-8"));if(fail)throw new java.io.IOException("offline");},()->now,"0.1.9");}
    static void tick(UsageCounter c){c.foreground();c.flush();}
    static JSONObject policy(boolean allowed,String reason)throws Exception{return new JSONObject().put("schema",1).put("allowed",allowed).put("reason",reason);}
    public static void main(String[] args)throws Exception{
        Memory memory=new Memory();UsageCounter c=counter(memory);
        check(c.enabled()&&memory.value.has("id"),"enabled by default with persisted random identity");tick(c);check(calls==1,"first foreground reports without consent");
        check(UsageCounter.day(Instant.parse("2026-10-08T16:00:00Z").toEpochMilli()).equals("2026-10-09"),"Beijing midnight boundary");
        check(last.length()==5&&last.getInt("schema")==2&&last.getString("app_version").equals("0.1.9")&&last.getJSONArray("operation_days").length()==0,"only five allowlisted payload fields, backwards-compatible identity");
        String id=last.getString("installation_id");check(UUID.fromString(id).version()==4,"random UUID4");
        check(last.getJSONArray("active_days").length()==1&&last.getJSONArray("active_days").getString(0).equals("2026-10-09"),"one dated activity");
        for(int i=0;i<20;i++)tick(c);check(calls==1,"repeated foreground does not resend acknowledged day");
        c=counter(memory);tick(c);check(calls==1&&c.identifier().equals(id),"restart preserves identity and acknowledged day");
        now+=86400000L;fail=true;tick(c);check(calls==2&&memory.value.getJSONArray("pending").length()==1,"offline keeps pending day");
        tick(c);check(calls==2,"retry backoff");c=counter(memory);tick(c);check(calls==2,"backoff survives restart");
        now+=UsageCounter.RETRY_MS;fail=false;tick(c);check(calls==3&&memory.value.getJSONArray("pending").length()==0,"later retry clears queue");
        fail=true;now+=86400000L;tick(c);now+=86400000L;tick(c);check(memory.value.getJSONArray("pending").length()==2,"distinct offline days");
        now+=UsageCounter.RETRY_MS;fail=false;tick(c);check(last.getJSONArray("active_days").length()==2,"batch offline dates");
        Memory migrated=new Memory();migrated.value=new JSONObject().put("decision","declined").put("id",id);
        UsageCounter upgrade=counter(migrated);check(upgrade.enabled()&&upgrade.identifier().equals(id),"0.1.9 migrates previous choice to default-on, preserves identity");
        check(!migrated.value.has("decision"),"obsolete consent state removed");
        Memory reinstalled=new Memory();UsageCounter fresh=counter(reinstalled);check(!fresh.identifier().equals(id),"reinstall creates distinct identity");
        Memory bounded=new Memory();UsageCounter offline=counter(bounded);fail=true;
        for(int i=0;i<100;i++){now+=86400000L;tick(offline);}check(bounded.value.getJSONArray("pending").length()==31,"bounded offline history");
        fail=false;now+=UsageCounter.RETRY_MS;tick(offline);check(last.getJSONArray("active_days").length()==31,"flush bounded history");
        Memory broken=new Memory();broken.fail=true;UsageCounter noDisk=counter(broken);check(!noDisk.enabled()&&noDisk.identifier().isEmpty(),"failed identity persistence suppresses collection");
        Memory disk=new Memory();UsageCounter diskCounter=counter(disk);diskCounter.foreground();disk.fail=true;int before=calls;diskCounter.flush();check(calls==before,"failed queue persistence suppresses network");
        Memory race=new Memory();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger sends=new AtomicInteger();
        UsageCounter concurrent=new UsageCounter(race,bytes->{sends.incrementAndGet();started.countDown();release.await();},()->now,"0.1.9");
        concurrent.foreground();Thread worker=new Thread(concurrent::flush);worker.start();started.await();concurrent.flush();check(sends.get()==1,"overlapping flush is deduplicated");
        now+=86400000L;concurrent.foreground();release.countDown();worker.join();check(race.value.getJSONArray("pending").length()==1,"old acknowledgment preserves newly added day");
        concurrent.flush();check(sends.get()==2,"new day can flush");
        Memory clockStore=new Memory();clockStore.value=new JSONObject().put("id",UUID.randomUUID().toString()).put("retry_at",now+864000000L).put("pending",new JSONArray().put(UsageCounter.day(now)));
        before=calls;counter(clockStore).flush();check(calls==before+1,"clock correction recovers retries");
        Memory corrupted=new Memory();corrupted.value=new JSONObject().put("id","hardware-id").put("ack_day",UsageCounter.day(now));check(!counter(corrupted).identifier().equals("hardware-id"),"invalid identity replaced");

        Memory operations=new Memory();UsageCounter uses=counter(operations);tick(uses);String installation=uses.identifier();int sent=calls;
        uses.operation(true,UsageCounter.STARTED);uses.operation(true,UsageCounter.SUCCEEDED);
        uses.operation(false,UsageCounter.STARTED);uses.operation(false,UsageCounter.FAILED);
        uses.operation(false,UsageCounter.STARTED);uses.operation(false,UsageCounter.CANCELLED);uses.flush();
        JSONObject report=last.getJSONArray("operation_days").getJSONObject(0);
        check(report.length()==3&&report.getJSONArray("extract").toString().equals("[1,1,0,0]")&&report.getJSONArray("summary").toString().equals("[2,0,1,1]"),"each outcome has its own cumulative counter");
        check(last.getJSONArray("active_days").length()==0&&calls==sent+1,"operations flush even when today's activity is already acknowledged");
        uses.flush();check(calls==sent+1,"acknowledged operation snapshot is not resent");
        uses=counter(operations);uses.flush();check(calls==sent+1&&uses.identifier().equals(installation),"restart preserves counter acknowledgments and installation");
        fail=true;uses.operation(true,UsageCounter.STARTED);uses.flush();String lost=last.getJSONArray("operation_days").toString();int failedCalls=calls;
        uses=counter(operations);uses.flush();check(calls==failedCalls,"operation retry backoff survives restart");
        now+=UsageCounter.RETRY_MS;fail=false;uses.flush();check(calls==failedCalls+1&&last.getJSONArray("operation_days").toString().equals(lost),"lost response retries same cumulative snapshot, not another increment");
        uses.operation(true,UsageCounter.SUCCEEDED);uses.flush();check(last.getJSONArray("operation_days").getJSONObject(0).getJSONArray("extract").toString().equals("[2,2,0,0]"),"new work increments after previous snapshot acknowledgment");
        now=Instant.parse("2026-10-09T15:59:59Z").toEpochMilli();Memory midnight=new Memory();UsageCounter midnightUses=counter(midnight);
        midnightUses.operation(false,UsageCounter.STARTED);now+=2000;midnightUses.operation(false,UsageCounter.SUCCEEDED);midnightUses.flush();
        check(last.getJSONArray("operation_days").length()==2&&last.getJSONArray("operation_days").getJSONObject(0).getString("day").equals("2026-10-09")&&last.getJSONArray("operation_days").getJSONObject(1).getJSONArray("summary").getLong(1)==1,"cross-midnight starts and outcomes use their own Beijing dates");
        Memory concurrentOps=new Memory();CountDownLatch opsStarted=new CountDownLatch(1),opsRelease=new CountDownLatch(1);ArrayList<JSONObject> reports=new ArrayList<>();
        UsageCounter inFlight=new UsageCounter(concurrentOps,bytes->{reports.add(new JSONObject(new String(bytes,"UTF-8")));if(reports.size()==1){opsStarted.countDown();opsRelease.await();}},()->now,"0.1.11");
        inFlight.operation(false,UsageCounter.STARTED);Thread operationThread=new Thread(inFlight::flush);operationThread.start();opsStarted.await();
        inFlight.operation(false,UsageCounter.SUCCEEDED);opsRelease.countDown();operationThread.join();inFlight.flush();
        check(reports.size()==2&&reports.get(0).getJSONArray("operation_days").getJSONObject(0).getJSONArray("summary").toString().equals("[1,0,0,0]")&&reports.get(1).getJSONArray("operation_days").getJSONObject(0).getJSONArray("summary").toString().equals("[1,1,0,0]"),"in-flight acknowledgment cannot erase newly completed work");
        Memory full=new Memory();UsageCounter history=counter(full);fail=true;
        for(int i=0;i<40;i++){now+=86400000L;history.operation(true,UsageCounter.STARTED);history.operation(true,UsageCounter.FAILED);history.flush();}
        check(full.value.getJSONArray("operations").length()==31,"operation history bounded to 31 days");
        now+=UsageCounter.RETRY_MS;fail=false;history.flush();check(last.getJSONArray("operation_days").length()==31&&last.toString().getBytes("UTF-8").length<=8192,"31-day payload fits server size limit");
        sent=calls;history.operation(true,99);history.flush();check(calls==sent,"invalid operation outcome cannot create telemetry");
        Memory oldStore=new Memory();oldStore.value=new JSONObject().put("id",installation).put("ack_day",UsageCounter.day(now)).put("pending",new JSONArray());UsageCounter upgraded=counter(oldStore);
        upgraded.operation(false,UsageCounter.STARTED);upgraded.flush();check(upgraded.identifier().equals(installation)&&last.getJSONArray("operation_days").getJSONObject(0).getJSONArray("summary").getLong(0)==1,"0.1.10 storage upgrades without inventing old usage or changing identity");
        Memory accessStore=new Memory();AtomicInteger fetches=new AtomicInteger();final JSONObject[] answer={policy(true,"")};final boolean[] offlinePolicy={true};
        AccessPolicy.Fetcher fetcher=()->{fetches.incrementAndGet();if(offlinePolicy[0])throw new java.io.IOException("offline");return answer[0];};
        AccessPolicy access=new AccessPolicy(accessStore,()->now,fetcher,id);access.refresh();check(access.allowed(),"first offline use allowed");
        access.refresh();check(fetches.get()==1,"policy failure cached one minute");
        now+=AccessPolicy.REFRESH_MS;offlinePolicy[0]=false;answer[0]=policy(false,"测试原因");access.refresh();check(!access.allowed(),"server denial applied");
        try{access.requireAllowed();throw new AssertionError("missing denial");}catch(IllegalStateException e){check(e.getMessage().contains("测试原因")&&e.getMessage().contains("已有笔记"),"denial explains reason and preserved history");}
        offlinePolicy[0]=true;AccessPolicy restarted=new AccessPolicy(accessStore,()->now,fetcher,id);restarted.refresh();check(!restarted.allowed(),"denial survives restart and offline");
        now+=AccessPolicy.REFRESH_MS;offlinePolicy[0]=false;answer[0]=policy(true,"old reason");restarted.refresh();check(restarted.allowed()&&accessStore.value.getString("reason").isEmpty(),"reenable clears reason");
        now+=AccessPolicy.REFRESH_MS;answer[0]=policy(false,"");restarted.refresh();check(!restarted.allowed(),"empty reason permitted");
        now+=AccessPolicy.REFRESH_MS;answer[0]=new JSONObject().put("schema",1).put("allowed","true").put("reason","");restarted.refresh();check(!restarted.allowed(),"malformed boolean cannot lift denial");
        now+=AccessPolicy.REFRESH_MS;answer[0]=policy(true,String.join("",Collections.nCopies(201,"x")));restarted.refresh();check(!restarted.allowed(),"oversized policy rejected atomically");
        AccessPolicy other=new AccessPolicy(accessStore,()->now,fetcher,UUID.randomUUID().toString());check(other.allowed(),"another installation does not inherit cached denial");
        now-=86400000L;answer[0]=policy(true,"");restarted.refresh();check(restarted.allowed(),"clock rollback cannot suppress permission refresh forever");
        System.out.println("PASS "+checks+" counting and access policy checks");
    }
}
