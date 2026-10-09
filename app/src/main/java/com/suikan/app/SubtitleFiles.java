package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

final class SubtitleFiles {
    static String text(JSONObject source,boolean timed){
        StringBuilder out=new StringBuilder();JSONArray rows=Json.array(source,"segments");
        for(int i=0;i<rows.length();i++){JSONObject s=rows.optJSONObject(i);if(s==null)continue;
            if(timed&&s.optDouble("start",-1)>=0)out.append('[').append(Transcript.stamp(s.optDouble("start"))).append("] ");
            out.append(s.optString("text")).append('\n');}return out.toString();
    }
    static boolean canSrt(JSONObject source){
        JSONArray rows=Json.array(source,"segments");if(rows.length()==0)return false;
        for(int i=0;i<rows.length();i++){JSONObject s=rows.optJSONObject(i);if(s==null)return false;double start=s.optDouble("start",-1),end=s.optDouble("end",-1);
            if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start)return false;}return true;
    }
    private static String time(double seconds){long ms=Math.round(seconds*1000);return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
    static String srt(JSONObject source){
        if(!canSrt(source))throw new IllegalArgumentException("字幕没有完整的起止时间，暂可保存 TXT");
        StringBuilder out=new StringBuilder();JSONArray rows=Json.array(source,"segments");
        for(int i=0;i<rows.length();i++){JSONObject s=rows.optJSONObject(i);out.append(i+1).append('\n').append(time(s.optDouble("start"))).append(" --> ").append(time(s.optDouble("end"))).append('\n').append(s.optString("text")).append("\n\n");}return out.toString();
    }
}
