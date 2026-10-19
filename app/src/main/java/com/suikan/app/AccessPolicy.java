package com.suikan.app;

import org.json.JSONObject;

/** Cache the last valid server decision. A transport failure never changes a decision. */
final class AccessPolicy {
    interface Fetcher { JSONObject fetch() throws Exception; }
    static final long REFRESH_MS=60000L;
    private final UsageCounter.Storage storage;
    private final UsageCounter.Clock clock;
    private final Fetcher fetcher;
    private final String identifier;
    private boolean allowed=true;
    private String reason="";
    private long retryAt;
    AccessPolicy(UsageCounter.Storage storage,UsageCounter.Clock clock,Fetcher fetcher,String identifier){
        this.storage=storage;this.clock=clock;this.fetcher=fetcher;this.identifier=identifier;
        JSONObject saved=storage.load();
        if(saved!=null&&identifier.equals(saved.optString("id")))try{accept(saved);}catch(Exception ignored){}
    }
    private void accept(JSONObject value)throws Exception{
        if(value.getInt("schema")!=1||!(value.get("allowed") instanceof Boolean)||!(value.get("reason") instanceof String))throw new IllegalArgumentException();
        String text=value.getString("reason");if(text.length()>200)throw new IllegalArgumentException();
        allowed=value.getBoolean("allowed");reason=allowed?"":text;
    }
    /** Serializes refreshes, off the main thread; caches both success and failure for one minute. */
    synchronized void refresh(){
        long now=clock.now();if(now<retryAt&&retryAt-now<=REFRESH_MS)return;retryAt=now+REFRESH_MS;
        try{
            JSONObject value=fetcher.fetch();accept(value);
            storage.save(new JSONObject().put("schema",1).put("id",identifier).put("allowed",allowed).put("reason",reason));
        }catch(Exception ignored){/* Preserve a previous denial, or allow first use when service is unreachable. */}
    }
    synchronized boolean allowed(){return allowed;}
    synchronized void requireAllowed(){if(!allowed)throw new IllegalStateException("此安装的使用权限已被管理员停用"+(reason.isEmpty()?"":"："+reason)+"。已有笔记仍可查看和导出；恢复权限后请重新尝试。");}
}
